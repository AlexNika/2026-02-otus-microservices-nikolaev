package ru.otus.hw.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import ru.otus.hw.models.Order;

import java.math.BigDecimal;

/**
 * DTO for {@link Order}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderResponseDto(Long id, Long userId, BigDecimal price, String description,
                               Order.OrderStatus orderStatus) {
}