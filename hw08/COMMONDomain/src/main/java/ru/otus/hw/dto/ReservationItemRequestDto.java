package ru.otus.hw.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * DTO for product reservation item request
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReservationItemRequestDto(
        @NotNull(message = "Product id can't be null")
        Long productId,
        @NotNull(message = "Quantity can't be null")
        @Min(value = 1, message = "Quantity must be at least 1")
        Integer quantity,
        @NotNull(message = "Idempotency key can't be null")
        Long idempotencyKey) {
}
