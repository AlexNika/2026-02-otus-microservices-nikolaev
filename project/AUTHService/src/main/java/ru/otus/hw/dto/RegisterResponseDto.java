package ru.otus.hw.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Registration response with the newly created credentials record")
public record RegisterResponseDto(
        @Schema(description = "User identifier (assigned by AuthService, propagated via UserCreatedEvent)",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Long id,

        @Schema(description = "User's email address", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        String email
) {
}
