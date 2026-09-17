package ru.otus.hw.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Partial update payload for a role")
public record UpdateRoleDto(
        @Schema(description = "New role description", example = "Support role",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String description
) {
}
