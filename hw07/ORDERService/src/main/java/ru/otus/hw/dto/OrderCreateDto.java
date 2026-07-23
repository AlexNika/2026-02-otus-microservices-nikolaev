package ru.otus.hw.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import ru.otus.hw.models.Order;

import java.math.BigDecimal;

/**
 * DTO for {@link Order}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderCreateDto(@NotNull
                             Long userId,
                             @NotNull @Digits(integer = 19, fraction = 4) @Positive
                             BigDecimal price,
                             String description) {
}