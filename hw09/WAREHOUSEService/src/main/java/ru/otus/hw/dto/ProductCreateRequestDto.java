package ru.otus.hw.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import ru.otus.hw.model.Product;

import java.math.BigDecimal;

/**
 * DTO for {@link Product}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductCreateRequestDto(
        String manufacturerArticle,
        @NotBlank(message = "SKU can't be blank")
        String sku,
        @NotBlank(message = "Product name can't be blank")
        String name,
        String description,
        @Min(0)
        BigDecimal price,
        @Valid
        ProductStockCreateRequestDto productStock) {
}
