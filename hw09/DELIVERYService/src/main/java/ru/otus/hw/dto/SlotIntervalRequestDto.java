package ru.otus.hw.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import ru.otus.hw.model.CourierSlot;

import java.time.LocalTime;

/**
 * DTO for {@link CourierSlot}
 */
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Delivery time slot interval to configure")
public record SlotIntervalRequestDto(
        @Schema(description = "Slot start time", example = "10:00",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "slotStart cannot be null")
        @JsonFormat(pattern = "HH:mm")
        LocalTime slotStart,
        @Schema(description = "Slot end time, must be after slotStart", example = "12:00",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "slotEnd cannot be null")
        @JsonFormat(pattern = "HH:mm")
        LocalTime slotEnd) {

    @JsonIgnore
    @AssertTrue(message = "slotStart must be before slotEnd")
    public boolean isTimeValid() {
        if (slotStart == null || slotEnd == null) {
            return true;
        }

        return slotStart.isBefore(slotEnd);
    }
}
