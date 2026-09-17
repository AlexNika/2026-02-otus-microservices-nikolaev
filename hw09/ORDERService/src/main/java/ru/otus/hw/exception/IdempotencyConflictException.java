package ru.otus.hw.exception;

import lombok.Getter;

/**
 * Конфликт ключа идемпотентности: ключ уже использован с другим payload'ом
 * (другой request_hash или другой владелец/userId). Маппится в 409 с кодом
 * {@link ErrorCodes#IDEMPOTENCY_KEY_CONFLICT}.
 */
@Getter
public class IdempotencyConflictException extends RuntimeException {

    private final String code = ErrorCodes.IDEMPOTENCY_KEY_CONFLICT;

    public IdempotencyConflictException(String message) {
        super(message);
    }
}
