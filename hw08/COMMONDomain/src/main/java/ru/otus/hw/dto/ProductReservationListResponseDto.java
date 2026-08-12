package ru.otus.hw.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;

import java.util.List;

/**
 * DTO for product reservations aggregated by order
 */
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductReservationListResponseDto(
        Long orderId,
        List<ProductReservationResponseDto> reservations) {
}
