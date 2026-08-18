package ru.otus.hw.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Min;
import lombok.Builder;
import ru.otus.hw.model.ProductStock;

import java.math.BigDecimal;

/**
 * DTO for {@link ProductStock}
 */
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductStockResponseDto(
        Long id,
        String manufacturerArticle,
        String sku,
        String name,
        String description,
        BigDecimal price,
        @Min(0)
        Integer availableQuantity,
        @Min(0)
        Integer reservedQuantity) {
}
