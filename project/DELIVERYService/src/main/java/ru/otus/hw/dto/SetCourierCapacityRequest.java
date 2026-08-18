package ru.otus.hw.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import ru.otus.hw.model.CourierDayCapacity;

import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * DTO for {@link CourierDayCapacity}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Request to set courier capacity for a date")
public record SetCourierCapacityRequest(
        @Schema(description = "Total number of couriers for the day", example = "5",
                requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0")
        @NotNull(message = "courierCount cannot be null")
        @Min(message = "Courier count must be greater than or equal to 0", value = 0)
        Integer courierCount,

        @Schema(description = "Optional list of non-overlapping slots. "
                + "If omitted or empty, default slots from the configuration are used")
        List<SlotIntervalRequestDto> slots) {


    /**
     * Если slots == null или пусто, сервис позже будет использовать default-слоты.
     * Если slots переданы, проверяем, что они не пересекаются.
     */
    @JsonIgnore
    @AssertTrue(message = "Slots must not overlap")
    public boolean isSlotsValid() {
        if (slots == null || slots.isEmpty()) {
            return true;
        }

        List<SlotIntervalRequestDto> nonNullSlots = slots.stream()
                .filter(Objects::nonNull)
                .toList();

        if (nonNullSlots.size() != slots.size()) {
            return true;
        }

        boolean hasNullTime = nonNullSlots.stream()
                .anyMatch(slot -> slot.slotStart() == null || slot.slotEnd() == null);

        if (hasNullTime) {
            return true;
        }

        List<SlotIntervalRequestDto> sortedSlots = nonNullSlots.stream()
                .sorted(Comparator.comparing(SlotIntervalRequestDto::slotStart))
                .toList();

        for (int i = 1; i < sortedSlots.size(); i++) {
            LocalTime previousEnd = sortedSlots.get(i - 1).slotEnd();
            LocalTime currentStart = sortedSlots.get(i).slotStart();

            if (currentStart.isBefore(previousEnd)) {
                return false;
            }
        }

        return true;
    }
}
