package ru.otus.hw.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import ru.otus.hw.models.Account;

import java.math.BigDecimal;

/**
 * DTO for {@link Account}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "DTO representing a user's billing account")
public record AccountResponseDto(
        @Schema(description = "Account identifier", example = "1",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Long id,

        @Schema(description = "Identifier of the user owning this account", example = "1",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Long userId,

        @Schema(description = "Current balance of the account", example = "1500.00",
                requiredMode = Schema.RequiredMode.REQUIRED)
        BigDecimal balance,

        @Schema(description = "Whether the account is enabled for operations", example = "true",
                requiredMode = Schema.RequiredMode.REQUIRED)
        boolean enabled,

        @Schema(description = "Whether the account is locked (blocked for operations)", example = "false",
                requiredMode = Schema.RequiredMode.REQUIRED)
        boolean locked) {
}
