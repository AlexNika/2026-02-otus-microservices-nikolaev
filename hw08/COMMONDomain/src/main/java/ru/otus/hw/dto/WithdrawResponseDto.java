package ru.otus.hw.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "DTO for the result of a withdrawal operation.")
public record WithdrawResponseDto(
        Long userId,
        Long accountId,
        @Schema(description = "Remaining balance after withdrawal", example = "3500.00",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Remaining balance cannot be null")
        BigDecimal newBalance,
        @Schema(description = "Whether the withdrawal was successful", example = "true",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Success flag cannot be null")
        Boolean success,
        @Schema(description = "Transaction ID of the withdrawal", example = "500",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Transaction ID cannot be null")
        Long transactionId
) {}