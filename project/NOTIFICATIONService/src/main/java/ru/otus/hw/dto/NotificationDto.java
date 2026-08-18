package ru.otus.hw.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import ru.otus.hw.model.Notification;

import java.time.LocalDateTime;

/**
 * DTO for {@link Notification}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NotificationDto(
        Long id,
        Long userId,
        String message,
        Long orderId,
        String notificationStatus,
        LocalDateTime created) {
}