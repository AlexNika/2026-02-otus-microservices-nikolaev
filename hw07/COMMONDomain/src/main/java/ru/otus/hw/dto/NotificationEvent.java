package ru.otus.hw.dto;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.Instant;

@Builder
public record NotificationEvent(
        Long orderId,
        Long userId,
        BigDecimal price,
        String status,
        String message,
        Instant timestamp) {
}
