package ru.otus.hw.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * DTO for delivery reservation
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Delivery reservation details")
public record DeliveryReservationResponse(
        @Schema(description = "Reservation identifier", example = "55")
        Long reservationId,

        @Schema(description = "Order identifier", example = "100")
        Long orderId,

        @Schema(description = "Delivery date", example = "2026-08-01")
        @JsonFormat(pattern = "yyyy-MM-dd")
        LocalDate date,

        @Schema(description = "Delivery slot start time", example = "10:00")
        @JsonFormat(pattern = "HH:mm")
        LocalTime slotStart,

        @Schema(description = "Delivery slot end time", example = "12:00")
        @JsonFormat(pattern = "HH:mm")
        LocalTime slotEnd,

        @Schema(description = "Courier number assigned to the delivery", example = "2")
        Integer assignedCourierNumber,

        @Schema(description = "Reservation status", example = "RESERVED",
                allowableValues = {"RESERVED", "CONFIRMED", "CANCELLED", "FAILED"})
        String status) {
}
