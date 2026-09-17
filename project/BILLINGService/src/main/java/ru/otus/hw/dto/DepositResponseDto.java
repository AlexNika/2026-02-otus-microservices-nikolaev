package ru.otus.hw.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import ru.otus.hw.models.Account;

import java.math.BigDecimal;

/**
 * DTO for {@link Account}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Result of a deposit operation")
public record DepositResponseDto(
        @Schema(description = "Identifier of the user owning the account", example = "1",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Long userId,

        @Schema(description = "Identifier of the account the funds were deposited into", example = "1",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Long accountId,

        @Schema(description = "Balance of the account after the deposit", example = "1600.00",
                requiredMode = Schema.RequiredMode.REQUIRED)
        BigDecimal newBalance,

        @Schema(description = "Identifier of the transaction created for this deposit", example = "42",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Long transactionId) {
}
