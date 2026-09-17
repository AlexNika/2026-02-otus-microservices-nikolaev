package ru.otus.hw.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import ru.otus.hw.models.Order;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * DTO for {@link Order}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "DTO representing a user's order")
public record OrderResponseDto(
        @Schema(description = "Order identifier", example = "1",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Long id,

        @Schema(description = "Identifier of the user owning this order", example = "1",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Long userId,

        @Schema(description = "Order price", example = "250.00",
                requiredMode = Schema.RequiredMode.REQUIRED)
        BigDecimal price,

        @Schema(description = "Free-form order description", example = "Birthday cake with delivery")
        String description,

        @Schema(description = "Identifier of the ordered product", example = "11",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Long productId,

        @Schema(description = "Ordered quantity", example = "3",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Integer quantity,

        @Schema(description = "Delivery date (yyyy-MM-dd)", example = "2026-09-10",
                requiredMode = Schema.RequiredMode.REQUIRED)
        LocalDate deliveryDate,

        @Schema(description = "Delivery time slot start (HH:mm)", example = "10:00",
                requiredMode = Schema.RequiredMode.REQUIRED)
        LocalTime slotStart,

        @Schema(description = "Delivery time slot end (HH:mm)", example = "12:00",
                requiredMode = Schema.RequiredMode.REQUIRED)
        LocalTime slotEnd,

        @Schema(description = "Order status: PENDING - created and awaiting the saga result, "
                + "PROCESSING - saga in progress, PLACED - saga completed successfully, "
                + "CANCELED - cancelled by the owner, FAILED - saga failed and compensated",
                example = "PLACED", requiredMode = Schema.RequiredMode.REQUIRED,
                allowableValues = {"PENDING", "PROCESSING", "PLACED", "CANCELED", "FAILED"})
        Order.OrderStatus orderStatus) {
}
