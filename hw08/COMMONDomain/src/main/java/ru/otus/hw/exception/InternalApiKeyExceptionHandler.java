package ru.otus.hw.exception;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import ru.otus.hw.dto.ErrorDto;

import java.time.LocalDateTime;

/**
 * Common handler mapping {@link InternalApiKeyException} (thrown by the internal API filter)
 * to 401 Unauthorized with a clear message.
 * <p>
 * {@link Order} with the highest precedence guarantees this exact-match handler wins over
 * generic {@code Exception}/{@code RuntimeException} handlers in service-specific advices.
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class InternalApiKeyExceptionHandler {

    @ExceptionHandler(InternalApiKeyException.class)
    public ResponseEntity<ErrorDto> handleInternalApiKeyException(@NonNull InternalApiKeyException ex) {
        log.warn("Internal API key check failed: {}", ex.getMessage());
        ErrorDto errorDto = ErrorDto.builder()
                .message(ex.getMessage())
                .status(HttpStatus.UNAUTHORIZED.value())
                .timestamp(LocalDateTime.now())
                .build();
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorDto);
    }
}
