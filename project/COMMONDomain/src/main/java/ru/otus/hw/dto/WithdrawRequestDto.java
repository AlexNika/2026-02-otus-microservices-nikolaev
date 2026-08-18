package ru.otus.hw.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "DTO for requesting withdrawal of funds from an account for an order.")
public record WithdrawRequestDto(
        @Schema(description = "User ID whose account to withdraw from", example = "1",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "User ID cannot be null")
        @Positive(message = "User ID must be positive")
        Long userId,

        @Schema(description = "Order ID for which the withdrawal is made", example = "100",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Order ID cannot be null")
        @Positive(message = "Order ID must be positive")
        Long orderId,

        @Schema(description = "Amount to withdraw", example = "1500.00",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Amount cannot be null")
        @Positive(message = "Amount must be positive")
        BigDecimal amount
) {
}
