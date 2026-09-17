package ru.otus.hw.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "Request body for updating the available quantity of a product stock")
public record ProductStockUpdateRequestDto(
        @Schema(description = "Quantity available for ordering, must not be negative", example = "5",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Available quantity can't be null")
        @Min(0)
        Integer availableQuantity) {
}
