package ru.otus.hw.exception;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.data.mapping.PropertyReferenceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
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

    @ExceptionHandler({AuthUserNotFoundException.class})
    public ResponseEntity<ErrorDto> handleNotFoundException(@NonNull RuntimeException ex) {
        log.warn("Not found: {}", ex.getMessage());
        return buildError(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<ErrorDto> handleDuplicateResourceException(@NonNull DuplicateResourceException ex) {
        log.warn("Duplicate resource: {}", ex.getMessage());
        return buildError(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ResponseEntity<ErrorDto> handleInvalidRefreshTokenException(@NonNull InvalidRefreshTokenException ex) {
        log.warn("Invalid refresh token: {}", ex.getMessage());
        return buildError(HttpStatus.UNAUTHORIZED, ex.getMessage());
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

    @ExceptionHandler(PropertyReferenceException.class)
    public ResponseEntity<ErrorDto> handlePropertyReferenceException(@NonNull PropertyReferenceException ex) {
        log.warn("Invalid property reference: {}", ex.getMessage());
        return buildError(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorDto> handleIllegalStateException(@NonNull IllegalStateException ex) {
        log.error("Illegal state: {}", ex.getMessage());
        return buildError(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage());
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorDto> handleAuthenticationException(@NonNull AuthenticationException ex) {
        log.warn("Authentication failed: {}", ex.getMessage());
        return buildError(HttpStatus.UNAUTHORIZED, "Authentication failed");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorDto> handleAccessDeniedException(@NonNull AccessDeniedException ex) {
        log.warn("Access denied: {}", ex.getMessage());
        return buildError(HttpStatus.FORBIDDEN, "Access denied");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorDto> handleGenericException(@NonNull Exception ex) {
        log.error("Unexpected error: {}", ex.getMessage());
        return buildError(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");
    }

    private @NonNull ResponseEntity<ErrorDto> buildError(@NonNull HttpStatus status, String message) {
        ErrorDto errorDto = ErrorDto.builder()
                .message(message)
                .status(status.value())
                .timestamp(LocalDateTime.now())
                .build();
        return ResponseEntity.status(status).body(errorDto);
    }
}
