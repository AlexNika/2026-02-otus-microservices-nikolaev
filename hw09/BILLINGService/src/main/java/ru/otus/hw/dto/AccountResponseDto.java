package ru.otus.hw.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import ru.otus.hw.models.Account;

import java.math.BigDecimal;

/**
 * DTO for {@link Account}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AccountResponseDto(
        Long id,
        Long userId,
        BigDecimal balance,
        boolean enabled,
        boolean locked) {
}