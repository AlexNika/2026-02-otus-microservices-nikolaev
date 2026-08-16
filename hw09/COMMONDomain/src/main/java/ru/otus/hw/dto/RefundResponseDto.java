package ru.otus.hw.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "DTO for the result of a refund operation.")
public record RefundResponseDto(
        @Schema(description = "Whether the refund was successful", example = "true",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Success flag cannot be null")
        Boolean success,

        @Schema(description = "Transaction ID of the refund", example = "501",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Transaction ID cannot be null")
        Long transactionId,

        @Schema(description = "New balance after refund", example = "5000.00",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "New balance cannot be null")
        BigDecimal newBalance,

        @Schema(description = "Error message if refund failed", example = "Account not found")
        String errorMessage
) {
}
