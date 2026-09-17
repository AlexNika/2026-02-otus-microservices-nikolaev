package ru.otus.hw.dto;

/**
 * DTO for {@link ru.otus.hw.models.Role}
 */
public record RoleCreateDto(Long id, String name, String description) {
}