package ru.otus.hw.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;

import java.time.Instant;

/**
 * Событие канала USER → BILLING: пользователь зарегистрирован, требуется биллинг-аккаунт.
 *
 * <p>{@code eventId} — ключ идемпотентности сообщения (UUID-строка, один на событие):
 * BILLING использует его как eventId уведомления ACCOUNT_CREATED, NOTIFICATION дедуплицирует
 * повторные доставки по нему. Идемпотентность самой обработки — по natural key {@code userId}
 * (unique accounts.user_id).
 */
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public record UserCreatedEvent(
        String eventId,
        Long userId,
        String email,
        Instant timestamp) {
}
