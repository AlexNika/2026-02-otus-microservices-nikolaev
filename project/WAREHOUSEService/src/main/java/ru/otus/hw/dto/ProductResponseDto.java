package ru.otus.hw.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import lombok.Builder;
import ru.otus.hw.model.Product;

import java.math.BigDecimal;

/**
 * DTO for {@link Product}
 */
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "DTO representing a product of the warehouse catalog")
public record ProductResponseDto(
        @Schema(description = "Product identifier", example = "1",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Long id,

        @Schema(description = "Manufacturer article of the product", example = "A-100",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String manufacturerArticle,

        @Schema(description = "Unique stock keeping unit of the product", example = "SKU-100",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String sku,

        @Schema(description = "Display name of the product", example = "Test product",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String name,

        @Schema(description = "Free-form description of the product", example = "d",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String description,

        @Schema(description = "Price of the product", example = "10.50",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @Min(0)
        BigDecimal price) {
}
