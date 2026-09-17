package ru.otus.hw.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Refresh request with the opaque refresh token")
public record RefreshRequestDto(
        @Schema(description = "Opaque refresh token (UUID) issued at login",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "Refresh token cannot be blank")
        String refreshToken
) {
}
