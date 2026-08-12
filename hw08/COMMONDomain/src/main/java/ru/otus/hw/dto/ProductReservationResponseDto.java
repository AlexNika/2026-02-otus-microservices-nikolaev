package ru.otus.hw.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;

import java.time.LocalDateTime;

/**
 * DTO for product reservation
 */
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductReservationResponseDto(
        Long id,
        Long orderId,
        Long productId,
        String sku,
        Integer quantity,
        ReservationStatus reservationStatus,
        Long idempotencyKey,
        Long version,
        LocalDateTime created,
        LocalDateTime updated) {
}
