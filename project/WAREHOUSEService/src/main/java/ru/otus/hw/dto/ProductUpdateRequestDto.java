package ru.otus.hw.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import ru.otus.hw.model.Product;

import java.math.BigDecimal;

/**
 * DTO for {@link Product}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Request body for a full update of an existing product")
public record ProductUpdateRequestDto(
        @Schema(description = "Manufacturer article of the product", example = "A-100",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String manufacturerArticle,

        @Schema(description = "SKU is immutable and ignored by the update operation", example = "SKU-100",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String sku,

        @Schema(description = "Display name of the product", example = "Test product",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "Product name can't be blank")
        String name,

        @Schema(description = "Free-form description of the product", example = "d",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String description,

        @Schema(description = "Price of the product, must not be negative; " +
                "null leaves the current price unchanged", example = "10.50",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Min(0)
        BigDecimal price,

        @Schema(description = "Stock update of the product; null leaves the stock unchanged",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Valid
        ProductStockUpdateRequestDto productStock) {
}
