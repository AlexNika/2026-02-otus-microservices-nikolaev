package ru.otus.hw.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import ru.otus.hw.model.Product;

import java.math.BigDecimal;

/**
 * DTO for partial update of {@link Product}.
 * <p>
 * All fields are optional: {@code null} values are ignored and the corresponding
 * product attributes are left unchanged.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductPatchRequestDto(
        String manufacturerArticle,
        String sku,
        String name,
        String description,
        @Min(0)
        BigDecimal price,
        @Valid
        ProductStockUpdateRequestDto productStock) {
}
