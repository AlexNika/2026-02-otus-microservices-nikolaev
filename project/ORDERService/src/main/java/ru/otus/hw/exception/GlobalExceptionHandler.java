package ru.otus.hw.exception;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import ru.otus.hw.dto.ErrorDto;

import static java.time.LocalDateTime.now;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Открытый circuit breaker держится {@code waitDurationInOpenState} (10с) - совет
     * клиенту повторить не раньше этого срока.
     */
    private static final String CIRCUIT_BREAKER_RETRY_AFTER_SECONDS = "10";

    @ExceptionHandler(NotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ResponseEntity<ErrorDto> handleNotFoundException(@NonNull NotFoundException ex) {
        log.warn("Order not found: {}", ex.getMessage());
        return buildError(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(BillingServiceException.class)
    public ResponseEntity<ErrorDto> handleBillingServiceException(@NonNull BillingServiceException ex) {
        ResponseEntity<ErrorDto> resilienceResponse = resilienceServiceUnavailable(ex.getCode(), ex.getMessage());
        if (resilienceResponse != null) {
            return resilienceResponse;
        }
        log.error("Billing service error: {}", ex.getMessage());
        return buildError(HttpStatus.BAD_GATEWAY, ex.getMessage());
    }

    @ExceptionHandler(SagaStepException.class)
    public ResponseEntity<ErrorDto> handleSagaStepException(@NonNull SagaStepException ex) {
        ResponseEntity<ErrorDto> resilienceResponse = resilienceServiceUnavailable(ex.getCode(), ex.getMessage());
        if (resilienceResponse != null) {
            return resilienceResponse;
        }
        log.error("Saga step {} failed: {}", ex.getStep(), ex.getMessage());
        return buildError(HttpStatus.CONFLICT, ex.getMessage(), ex.getCode());
    }

    @ExceptionHandler(OrderStateConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ResponseEntity<ErrorDto> handleOrderStateConflictException(@NonNull OrderStateConflictException ex) {
        log.warn("Order state conflict: {}", ex.getMessage());
        return buildError(HttpStatus.CONFLICT, ex.getMessage(), ex.getCode());
    }

    @ExceptionHandler(IdempotencyKeyFormatException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ResponseEntity<ErrorDto> handleIdempotencyKeyFormatException(@NonNull IdempotencyKeyFormatException ex) {
        log.warn("Malformed Idempotency-Key header: {}", ex.getMessage());
        return buildError(HttpStatus.BAD_REQUEST, ex.getMessage(), ErrorCodes.MALFORMED_REQUEST);
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ResponseEntity<ErrorDto> handleIdempotencyConflictException(@NonNull IdempotencyConflictException ex) {
        log.warn("Idempotency key conflict: {}", ex.getMessage());
        return buildError(HttpStatus.CONFLICT, ex.getMessage(), ex.getCode());
    }

    /**
     * Отказы method-security (@PreAuthorize/@PostAuthorize) НЕ глотаем:
     * пробрасываем в ExceptionTranslationFilter -> единообразный 403 JSON.
     */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public void handleAccessDeniedException(org.springframework.security.access.@NonNull AccessDeniedException ex)
            throws org.springframework.security.access.AccessDeniedException {
        throw ex;
    }

    @ExceptionHandler(RuntimeException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ResponseEntity<ErrorDto> handleRuntimeException(@NonNull RuntimeException ex) {
        if (ex.getMessage() != null && ex.getMessage().contains("Insufficient funds")) {
            log.warn("Insufficient funds: {}", ex.getMessage());
            return buildError(HttpStatus.PAYMENT_REQUIRED, ex.getMessage());
        }
        log.error("Runtime error: {}", ex.getMessage());
        return buildError(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    private @NonNull ResponseEntity<ErrorDto> buildError(@NonNull HttpStatus status, @NonNull String message) {
        return buildError(status, message, null);
    }

    private @NonNull ResponseEntity<ErrorDto> buildError(@NonNull HttpStatus status, @NonNull String message,
                                                         String code) {
        return ResponseEntity.status(status)
                .body(ErrorDto.builder()
                        .message(message)
                        .status(status.value())
                        .timestamp(now())
                        .code(code)
                        .build());
    }

    /**
     * Отказ из-за ограничений устойчивости (открытый circuit breaker / исчерпанный
     * {@code RateLimiter}) - downstream временно недоступен: 503 вместо обычной ошибки шага.
     * Для кода {@link ErrorCodes#CIRCUIT_BREAKER_OPEN} добавляется {@code Retry-After}.
     * Для прочих кодов возвращается {@code null} (обычный маппинг).
     */
    private ResponseEntity<ErrorDto> resilienceServiceUnavailable(String code, @NonNull String message) {
        if (ErrorCodes.CIRCUIT_BREAKER_OPEN.equals(code)) {
            log.error("Downstream rejected by open circuit breaker: {}", message);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header(HttpHeaders.RETRY_AFTER, CIRCUIT_BREAKER_RETRY_AFTER_SECONDS)
                    .body(ErrorDto.builder()
                            .message(message)
                            .status(HttpStatus.SERVICE_UNAVAILABLE.value())
                            .timestamp(now())
                            .code(code)
                            .build());
        }
        if (ErrorCodes.RATE_LIMITED.equals(code)) {
            log.error("Downstream rejected by rate limiter: {}", message);
            return buildError(HttpStatus.SERVICE_UNAVAILABLE, message, code);
        }
        return null;
    }
}
