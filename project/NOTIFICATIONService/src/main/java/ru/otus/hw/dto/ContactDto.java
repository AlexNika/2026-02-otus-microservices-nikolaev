package ru.otus.hw.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Внутренний контракт read-модели контактов для эндпоинта
 * {@code GET /internal/contacts/user/{userId}}. В COMMONDomain не выносится:
 * это контракт одного сервиса (верификация репликации и будущие внутренние потребители).
 */
@Schema(description = "Internal read-model of replicated user contacts (not exposed in Swagger UI)")
public record ContactDto(
        @Schema(description = "ID of the user the contacts belong to", example = "7")
        Long userId,
        @Schema(description = "User email replicated from USERService", example = "user@example.com")
        String email,
        @Schema(description = "User phone replicated from USERService", example = "+79991234567")
        String phone,
        @Schema(description = "Timestamp of the last successful replication",
                example = "2026-09-07T12:34:56Z")
        Instant updatedAt) {
}
