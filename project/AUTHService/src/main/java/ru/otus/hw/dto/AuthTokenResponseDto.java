package ru.otus.hw.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Пара токенов, возвращаемая AuthService при login/refresh.<br>
 * {@code accessToken} - короткоживущий JWT (HMAC, 15 мин),<br>
 * {@code refreshToken} - opaque UUID (30 дней) с ротацией при каждом refresh.
 */
@Schema(description = "Access + refresh token pair issued by AuthService")
public record AuthTokenResponseDto(
        @Schema(description = "JWT access token (15 min TTL)", requiredMode = Schema.RequiredMode.REQUIRED)
        String accessToken,

        @Schema(description = "Opaque refresh token (30 days TTL, rotated on refresh)",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String refreshToken,

        @Schema(description = "Token type", example = "Bearer", requiredMode = Schema.RequiredMode.REQUIRED)
        String tokenType,

        @Schema(description = "User identifier (resource key across all services)",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Long userId,

        @Schema(description = "User's email address", requiredMode = Schema.RequiredMode.REQUIRED)
        String email,

        @Schema(description = "User roles", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<String> roles
) {

    public AuthTokenResponseDto(String accessToken, String refreshToken, Long userId, String email,
            List<String> roles) {
        this(accessToken, refreshToken, "Bearer", userId, email, roles);
    }
}
