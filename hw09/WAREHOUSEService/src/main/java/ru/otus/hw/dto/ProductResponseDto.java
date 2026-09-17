package ru.otus.hw.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Min;
import lombok.Builder;
import ru.otus.hw.model.Product;

import java.math.BigDecimal;

/**
 * DTO for {@link Product}
 */
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductResponseDto(
        Long id,
        String manufacturerArticle,
        String sku,
        String name,
        String description,
        @Min(0)
        BigDecimal price) {
}