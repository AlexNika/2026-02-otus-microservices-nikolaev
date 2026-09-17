package ru.otus.hw.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import ru.otus.hw.model.ProductStock;

/**
 * DTO for {@link ProductStock} creation
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Request body for creating the initial stock of a product")
public record ProductStockCreateRequestDto(
        @Schema(description = "Quantity available for ordering, must not be negative", example = "5",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Available quantity can't be null")
        @Min(0)
        Integer availableQuantity,

        @Schema(description = "Quantity reserved by active reservations, must not be negative", example = "0",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Reserved quantity can't be null")
        @Min(0)
        Integer reservedQuantity) {
}
