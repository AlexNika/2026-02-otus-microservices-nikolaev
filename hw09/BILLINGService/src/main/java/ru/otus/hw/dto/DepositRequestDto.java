package ru.otus.hw.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import ru.otus.hw.models.Account;

import java.math.BigDecimal;

/**
 * DTO for {@link Account}.
 *
 * <p>{@code idempotencyKey} — опциональный ключ идемпотентности пополнения: повтор запроса
 * с тем же ключом возвращает прежнюю транзакцию, с другой суммой — 409
 * IDEMPOTENCY_KEY_CONFLICT. Поле аддитивное: старые запросы без него работают как раньше.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DepositRequestDto(
        @NotNull
        @Positive
        BigDecimal amount,

        @Size(max = 64)
        String idempotencyKey) {
}