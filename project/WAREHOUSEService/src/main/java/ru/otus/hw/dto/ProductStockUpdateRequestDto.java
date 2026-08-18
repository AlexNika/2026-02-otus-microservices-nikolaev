package ru.otus.hw.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import ru.otus.hw.model.ProductStock;

/**
 * DTO for {@link ProductStock}
 * <p>
 * {@code reservedQuantity} is managed by the system via reservations and is not
 * updatable through this DTO.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductStockUpdateRequestDto(
        @NotNull(message = "Available quantity can't be null")
        @Min(0)
        Integer availableQuantity) {
}