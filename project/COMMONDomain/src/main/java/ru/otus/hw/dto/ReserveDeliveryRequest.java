package ru.otus.hw.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * DTO for delivery reservation request
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Request to reserve delivery for an order")
public record ReserveDeliveryRequest(
        @Schema(description = "Order identifier", example = "100", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "orderId cannot be null")
        @Positive(message = "orderId must be positive")
        Long orderId,

        @Schema(description = "Delivery date, today or in the future", example = "2026-08-01",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "date cannot be null")
        @FutureOrPresent(message = "delivery date must be today or in the future")
        @JsonFormat(pattern = "yyyy-MM-dd")
        LocalDate date,

        @Schema(description = "Delivery slot start time", example = "10:00",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "slotStart cannot be null")
        @JsonFormat(pattern = "HH:mm")
        LocalTime slotStart,

        @Schema(description = "Delivery slot end time, must be after slotStart", example = "12:00",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "slotEnd cannot be null")
        @JsonFormat(pattern = "HH:mm")
        LocalTime slotEnd,

        @Schema(description = "Owner of the order (assigned by ORDERService from its JWT; used for "
                + "ownership checks of the reservation). Optional for backward compatibility.",
                example = "7", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Long userId
        ) {

    @JsonIgnore
    @AssertTrue(message = "slotStart must be before slotEnd")
    public boolean isTimeValid() {
        if (slotStart == null || slotEnd == null) {
            return true;
        }

        return slotStart.isBefore(slotEnd);
    }
}
