package ru.otus.hw.exception;

/**
 * Credentials-запись пользователя не найдена - 404.
 */
public class AuthUserNotFoundException extends RuntimeException {

    public AuthUserNotFoundException(String message) {
        super(message);
    }
}
