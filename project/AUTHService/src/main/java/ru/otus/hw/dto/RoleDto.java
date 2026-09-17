package ru.otus.hw.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import ru.otus.hw.models.Role;

/**
 * DTO for {@link Role}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "DTO for role entity")
public record RoleDto(
        @Schema(description = "Role identifier", example = "1", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Long id,

        @Schema(description = "Role name", example = "ADMIN", requiredMode = Schema.RequiredMode.REQUIRED)
        String name,

        @Schema(description = "Role description", example = "Administrator role",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String description
) {
}
