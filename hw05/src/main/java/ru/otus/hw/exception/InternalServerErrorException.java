package ru.otus.hw.exception;

/**
 * Исключение для явной генерации внутренних ошибок сервера (HTTP 500).
 * Используется для демонстрации мониторинга и алертинга.
 */
public class InternalServerErrorException extends RuntimeException {

    public InternalServerErrorException(String message) {
        super(message);
    }

    public InternalServerErrorException(String message, Throwable cause) {
        super(message, cause);
    }
}
