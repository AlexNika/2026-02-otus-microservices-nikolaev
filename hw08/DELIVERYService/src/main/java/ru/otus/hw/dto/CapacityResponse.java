package ru.otus.hw.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import ru.otus.hw.model.CourierDayCapacity;

import java.time.LocalDate;
import java.util.List;

/**
 * DTO for {@link CourierDayCapacity}
 */

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Configured courier capacity for a date")
public record CapacityResponse(

        @Schema(description = "Date the capacity is configured for", example = "2026-08-01")
        @JsonFormat(pattern = "yyyy-MM-dd")
        LocalDate capacityDate,

        @Schema(description = "Total number of couriers for the day", example = "5")
        Integer courierCount,

        @Schema(description = "Courier slots for the day with capacity and reservation counters")
        List<CapacitySlotResponse> slots) {
}
