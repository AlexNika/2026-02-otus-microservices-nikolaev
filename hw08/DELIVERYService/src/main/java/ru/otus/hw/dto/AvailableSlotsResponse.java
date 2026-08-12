package ru.otus.hw.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.time.LocalDate;
import java.util.List;

@Builder
@Schema(description = "List of delivery time slots for a date with availability flags")
public record AvailableSlotsResponse(

        @Schema(description = "Date the slots are provided for", example = "2026-08-01")
        @JsonFormat(pattern = "yyyy-MM-dd")
        LocalDate date,

        @Schema(description = "Delivery time slots for the date")
        List<AvailableSlotResponse> slots

) {
}
