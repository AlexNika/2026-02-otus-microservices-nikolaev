package ru.otus.hw.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import ru.otus.hw.models.Role;


/**
 * DTO for {@link Role}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RoleDto(Long id, String name, String description) {
}
