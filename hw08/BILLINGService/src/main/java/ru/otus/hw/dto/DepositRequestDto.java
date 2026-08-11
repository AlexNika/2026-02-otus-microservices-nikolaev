package ru.otus.hw.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import ru.otus.hw.models.Account;

import java.math.BigDecimal;

/**
 * DTO for {@link Account}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DepositRequestDto(
        @NotNull
        @Positive
        BigDecimal amount) {
}