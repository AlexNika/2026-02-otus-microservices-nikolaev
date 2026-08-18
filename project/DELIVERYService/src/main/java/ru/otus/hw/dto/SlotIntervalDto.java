package ru.otus.hw.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import ru.otus.hw.model.CourierSlot;

import java.time.LocalTime;

/**
 * DTO for {@link CourierSlot}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Delivery time slot interval")
public record SlotIntervalDto(

        @Schema(description = "Slot start time", example = "10:00")
        @JsonFormat(pattern = "HH:mm")
        LocalTime slotStart,

        @Schema(description = "Slot end time", example = "12:00")
        @JsonFormat(pattern = "HH:mm")
        LocalTime slotEnd) {
}
