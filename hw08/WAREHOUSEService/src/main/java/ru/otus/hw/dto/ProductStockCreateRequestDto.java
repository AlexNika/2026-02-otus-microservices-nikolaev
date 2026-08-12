package ru.otus.hw.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import ru.otus.hw.model.ProductStock;

/**
 * DTO for {@link ProductStock} creation
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductStockCreateRequestDto(
        @NotNull(message = "Available quantity can't be null")
        @Min(0)
        Integer availableQuantity,
        @NotNull(message = "Reserved quantity can't be null")
        @Min(0)
        Integer reservedQuantity) {
}
