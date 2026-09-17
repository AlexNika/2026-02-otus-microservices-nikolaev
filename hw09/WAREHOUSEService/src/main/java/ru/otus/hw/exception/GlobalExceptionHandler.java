package ru.otus.hw.exception;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import ru.otus.hw.dto.ErrorDto;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorDto> handleUserNotFoundException(@NonNull NotFoundException ex) {
        log.warn("Product not found: {}", ex.getMessage());
        return buildError(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<ErrorDto> handleDuplicateResourceException(@NonNull DuplicateResourceException ex) {
        log.warn("Product is duplicated: {}", ex.getMessage());
        return buildError(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(ProductReservationException.class)
    public ResponseEntity<ErrorDto> handleProductReservationException(@NonNull ProductReservationException ex) {
        log.warn("Product reservation failed: {}", ex.getMessage());
        return buildError(HttpStatus.CONFLICT, ex.getMessage(), ex.getCode());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorDto> handleConstraintViolationException(@NonNull ConstraintViolationException ex) {
        log.warn("Validation failed: {}", ex.getMessage());
        String message = ex.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .collect(Collectors.joining(", "));
        if (message.isEmpty()) {
            message = ex.getMessage();
        }
        return buildError(HttpStatus.BAD_REQUEST, message, ErrorCodes.VALIDATION_FAILED);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorDto> handleHttpMessageNotReadableException(
            @NonNull HttpMessageNotReadableException ex) {
        log.warn("Malformed request body: {}", ex.getMessage());
        return buildError(HttpStatus.BAD_REQUEST, "Malformed request body", ErrorCodes.MALFORMED_REQUEST);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorDto> handleDataIntegrityViolationException(
            @NonNull DataIntegrityViolationException ex) {
        log.warn("Data integrity violation: {}", ex.getMessage());
        return buildError(HttpStatus.CONFLICT, "Data integrity violation", ErrorCodes.DATA_INTEGRITY_VIOLATION);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorDto> handleOptimisticLockingFailureException(
            @NonNull OptimisticLockingFailureException ex) {
        log.warn("Concurrent modification: {}", ex.getMessage());
        return buildError(HttpStatus.CONFLICT, "Concurrent modification detected, retry the operation",
                ErrorCodes.CONCURRENT_MODIFICATION);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorDto> handleValidationException(@NonNull MethodArgumentNotValidException ex) {
        log.warn("Validation failed: {}", ex.getMessage());
        Map<String, String> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        f -> Objects.requireNonNullElse(f.getDefaultMessage(), "Invalid value"),
                        (a, _) -> a,
                        LinkedHashMap::new
                ));
        String message = "Validation failed: " + fieldErrors;
        return buildError(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorDto> handleIllegalArgumentException(@NonNull IllegalArgumentException ex) {
        log.warn("Illegal argument: {}", ex.getMessage());
        return buildError(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorDto> handleGenericException(Exception ex) {
        log.error("Unexpected error: {}", ex.getMessage(), ex);
        return buildError(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");
    }

    private @NonNull ResponseEntity<ErrorDto> buildError(@NonNull HttpStatus status, String message) {
        return buildError(status, message, null);
    }

    private @NonNull ResponseEntity<ErrorDto> buildError(@NonNull HttpStatus status, String message, String code) {
        ErrorDto errorDto = ErrorDto.builder()
                .message(message)
                .status(status.value())
                .timestamp(LocalDateTime.now())
                .code(code)
                .build();
        return ResponseEntity.status(status).body(errorDto);
    }
}
