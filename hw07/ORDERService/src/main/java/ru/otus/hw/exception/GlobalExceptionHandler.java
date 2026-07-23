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

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ResponseEntity<ErrorDto> handleIllegalStateException(@NonNull IllegalStateException ex) {
        log.warn("Illegal order state: {}", ex.getMessage());
        return buildError(HttpStatus.BAD_REQUEST, ex.getMessage());
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
        return ResponseEntity.status(status)
                .body(ErrorDto.builder()
                        .message(message)
                        .status(status.value())
                        .timestamp(now())
                        .build());
    }
}
