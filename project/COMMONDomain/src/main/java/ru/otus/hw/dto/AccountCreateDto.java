package ru.otus.hw.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "DTO for creating a new account for user with userId as identity.")
public record AccountCreateDto(
        @Schema(description = "User's userId (for account)", example = "1",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "UserId can not be null")
        @Positive(message = "UserId must be positive")
        Long userId
) {}
