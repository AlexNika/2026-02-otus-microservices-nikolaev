package ru.otus.hw.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import ru.otus.hw.model.Notification;

import java.time.LocalDateTime;

/**
 * DTO for {@link Notification}
 */
@Schema(description = "User notification produced by order saga or registration lifecycle events")
@JsonIgnoreProperties(ignoreUnknown = true)
public record NotificationDto(
        @Schema(description = "Notification ID", example = "42")
        Long id,
        @Schema(description = "ID of the user the notification belongs to", example = "7")
        Long userId,
        @Schema(description = "Human-readable notification text",
                example = "Order 15 payment completed successfully")
        String message,
        @Schema(description = "Related order ID; null for registration lifecycle notifications",
                example = "15")
        Long orderId,
        @Schema(description = "Source status as-is: SUCCESS/FAILED for orders "
                + "or a registration lifecycle status (USER_CREATED, ACCOUNT_CREATED, ...)",
                example = "SUCCESS")
        String notificationStatus,
        @Schema(description = "Creation timestamp", example = "2026-09-07T12:34:56")
        LocalDateTime created) {
}
