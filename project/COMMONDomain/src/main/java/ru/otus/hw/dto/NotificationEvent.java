package ru.otus.hw.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Событие для канала уведомлений ORDER → NOTIFICATION.
 *
 * <p>{@code eventId} - ключ идемпотентности сообщения (UUID-строка, один на событие):
 * consumer дедуплицирует повторные доставки по нему. Поле аддитивное: старые сообщения
 * без него десериализуются с {@code eventId == null} (legacy-путь).
 */
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public record NotificationEvent(
        String eventId,
        Long orderId,
        Long userId,
        BigDecimal price,
        String status,
        String message,
        Instant timestamp) {
}
