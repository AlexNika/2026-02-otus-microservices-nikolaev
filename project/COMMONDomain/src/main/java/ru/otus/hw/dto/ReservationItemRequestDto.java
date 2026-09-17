package ru.otus.hw.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * DTO for product reservation item request.
 *
 * <p>{@code idempotencyKey} - строковый детерминированный ключ резерва
 * (контракт ORDER→WAREHOUSE: {@code "order-{orderId}-p{productId}"}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReservationItemRequestDto(
        @NotNull(message = "Product id can't be null")
        Long productId,
        @NotNull(message = "Quantity can't be null")
        @Min(value = 1, message = "Quantity must be at least 1")
        Integer quantity,
        @NotBlank(message = "Idempotency key can't be blank")
        @Size(max = 64, message = "Idempotency key must be at most 64 characters")
        String idempotencyKey) {
}
