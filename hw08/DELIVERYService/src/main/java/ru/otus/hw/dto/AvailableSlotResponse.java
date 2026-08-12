package ru.otus.hw.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.time.LocalTime;

@Builder
@Schema(description = "Delivery time slot with an availability flag")
public record AvailableSlotResponse(

        @Schema(description = "Slot start time", example = "10:00")
        @JsonFormat(pattern = "HH:mm")
        LocalTime slotStart,

        @Schema(description = "Slot end time", example = "12:00")
        @JsonFormat(pattern = "HH:mm")
        LocalTime slotEnd,

        @Schema(description = "Whether the slot is available for reservation", example = "true")
        boolean available

) {
}
