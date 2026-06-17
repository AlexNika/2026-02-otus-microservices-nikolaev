package ru.otus.hw.exception;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
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

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ErrorDto> handleUserNotFoundException(@NonNull UserNotFoundException ex) {
        log.warn("User not found: {}", ex.getMessage());
        return buildError(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<ErrorDto> handleDuplicateResourceException(@NonNull DuplicateResourceException ex) {
        log.warn("User user is duplicated: {}", ex.getMessage());
        return buildError(HttpStatus.CONFLICT, ex.getMessage());
    }

@ExceptionHandler(MethodArgumentNotValidException.class)
public ResponseEntity<ErrorDto> handleValidationException(@NonNull MethodArgumentNotValidException ex) {
    log.warn("Validation failed: {}", ex.getMessage());
    Map<String, String> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
        .collect(Collectors.toMap(
            FieldError::getField,
            f -> Objects.requireNonNullElse(f.getDefaultMessage(), "Invalid value"),
            (a, b) -> a,
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

    @ExceptionHandler(InternalServerErrorException.class)
    public ResponseEntity<ErrorDto> handleInternalServerErrorException(@NonNull InternalServerErrorException ex) {
        log.error("Internal server error: {}", ex.getMessage());
        return buildError(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage());
    }

    @ExceptionHandler(InvalidJwtException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ResponseEntity<ErrorDto> handleInvalidJwtException(@NonNull InvalidJwtException ex) {
        log.warn("Invalid JWT: {}", ex.getMessage());
        return buildError(HttpStatus.FORBIDDEN, "Invalid JWT");
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorDto> handleAuthenticationException(@NonNull AuthenticationException ex) {
        log.warn("Authentication failed: {}", ex.getMessage());
        return buildError(HttpStatus.UNAUTHORIZED, "Authentication required");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorDto> handleAccessDeniedException(@NonNull AccessDeniedException ex) {
        log.warn("Access denied: {}", ex.getMessage());
        return buildError(HttpStatus.FORBIDDEN, "Access denied");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorDto> handleGenericException(Exception ex) {
        log.error("Unexpected error: {}", ex.getMessage(), ex);
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
