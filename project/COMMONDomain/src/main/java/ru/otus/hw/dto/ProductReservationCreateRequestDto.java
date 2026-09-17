package ru.otus.hw.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * DTO for product reservation request
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductReservationCreateRequestDto(
        @NotNull(message = "Order id can't be null")
        Long orderId,
        @NotEmpty(message = "Reservation items can't be empty")
        List<@Valid ReservationItemRequestDto> items) {
}
