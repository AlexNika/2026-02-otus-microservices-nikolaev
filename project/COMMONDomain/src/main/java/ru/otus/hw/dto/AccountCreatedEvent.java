package ru.otus.hw.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;

import java.time.Instant;

/**
 * Событие канала BILLING → USER: биллинг-аккаунт для пользователя готов.
 *
 * <p>Идемпотентность потребления - по natural key {@code userId}: пользователь переводится
 * в ACTIVE из PENDING/BLOCKED; повторные доставки безопасны.
 */
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public record AccountCreatedEvent(
        String eventId,
        Long userId,
        Long accountId,
        Instant timestamp) {
}
