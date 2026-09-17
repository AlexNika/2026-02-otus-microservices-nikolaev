package ru.otus.hw.exception;

/**
 * Refresh-токен невалиден/истёк/повторно использован - всегда 401.
 */
public class InvalidRefreshTokenException extends RuntimeException {

    public InvalidRefreshTokenException(String message) {
        super(message);
    }
}
