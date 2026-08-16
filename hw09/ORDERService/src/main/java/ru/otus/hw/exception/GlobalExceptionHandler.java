package ru.otus.hw.exception;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
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

    @ExceptionHandler(NotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ResponseEntity<ErrorDto> handleNotFoundException(@NonNull NotFoundException ex) {
        log.warn("Order not found: {}", ex.getMessage());
        return buildError(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(BillingServiceException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public ResponseEntity<ErrorDto> handleBillingServiceException(@NonNull BillingServiceException ex) {
        log.error("Billing service error: {}", ex.getMessage());
        return buildError(HttpStatus.BAD_GATEWAY, ex.getMessage());
    }

    @ExceptionHandler(SagaStepException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ResponseEntity<ErrorDto> handleSagaStepException(@NonNull SagaStepException ex) {
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
}
