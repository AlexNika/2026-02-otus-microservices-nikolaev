package ru.otus.hw.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.time.LocalTime;

@Builder(toBuilder = true)
@Schema(description = "Courier slot with capacity and reservation counters")
public record CapacitySlotResponse(

        @Schema(description = "Slot identifier", example = "10")
        Long slotId,

        @Schema(description = "Slot start time", example = "10:00")
        @JsonFormat(pattern = "HH:mm")
        LocalTime slotStart,

        @Schema(description = "Slot end time", example = "12:00")
        @JsonFormat(pattern = "HH:mm")
        LocalTime slotEnd,

        @Schema(description = "Total courier capacity of the slot", example = "3")
        Integer capacity,

        @Schema(description = "Number of active reservations in the slot", example = "1")
        Integer reservedCount

) {
}
