package ru.otus.hw.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import ru.otus.hw.models.Order;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * DTO for {@link Order}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderResponseDto(Long id, Long userId, BigDecimal price, String description,
                               Long productId, Integer quantity,
                               LocalDate deliveryDate, LocalTime slotStart, LocalTime slotEnd,
                               Order.OrderStatus orderStatus) {
}
