package ru.otus.hw.exception;

/**
 * Заголовок Idempotency-Key имеет неверный формат (ожидается UUID).
 * Маппится в 400 с кодом {@link ErrorCodes#MALFORMED_REQUEST}.
 */
public class IdempotencyKeyFormatException extends RuntimeException {

    public IdempotencyKeyFormatException(String value) {
        super("Idempotency-Key header must be a valid UUID, got: " + value);
    }
}
