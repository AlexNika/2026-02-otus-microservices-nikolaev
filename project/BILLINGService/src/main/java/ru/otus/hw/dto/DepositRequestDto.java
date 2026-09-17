package ru.otus.hw.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import ru.otus.hw.models.Account;

import java.math.BigDecimal;

/**
 * DTO for {@link Account}.
 *
 * <p>{@code idempotencyKey} - опциональный ключ идемпотентности пополнения: повтор запроса
 * с тем же ключом возвращает прежнюю транзакцию, с другой суммой - 409
 * IDEMPOTENCY_KEY_CONFLICT. Поле аддитивное: старые запросы без него работают как раньше.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "DTO for depositing funds into an account")
public record DepositRequestDto(
        @Schema(description = "Amount to deposit", example = "100.00",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        @Positive
        BigDecimal amount,

        @Schema(description = "Optional idempotency key: a repeated request with the same key returns " +
                "the original transaction, the same key with a different amount is rejected with 409 " +
                "IDEMPOTENCY_KEY_CONFLICT",
                example = "depo-2026-0001",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Size(max = 64)
        String idempotencyKey) {
}
