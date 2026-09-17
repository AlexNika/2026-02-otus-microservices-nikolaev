package ru.otus.hw.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * Админ-представление credentials-записи пользователя (без password_hash).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Admin view of a user credentials record without password_hash")
public record AuthUserDto(
        @Schema(description = "User identifier", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
        Long id,

        @Schema(description = "User's email address", example = "john.doe@example.com",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String email,

        @Schema(description = "Record creation timestamp", example = "2026-08-31T10:00:00",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        LocalDateTime createdAt
) {
}
