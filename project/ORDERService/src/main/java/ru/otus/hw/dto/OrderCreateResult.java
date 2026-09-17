package ru.otus.hw.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Результат создания заказа через POST /api/v1/order с учётом обработки Idempotency-Key.
 *
 * <p>{@link Kind#CREATED} - заказ создан впервые (201);
 * {@link Kind#REPLAYED_COMPLETED} - повтор ключа, сага уже завершена успешно: возвращается
 * сохранённый ответ (201 + тот же заказ);
 * {@link Kind#REPLAYED_CURRENT} - повтор ключа, сага в процессе или завершилась провалом:
 * возвращается текущее состояние заказа (200), второй заказ не создаётся.
 */
public record OrderCreateResult(
        @Schema(description = "Current (or stored after a successful saga) state of the order; "
                + "the HTTP response body is OrderResponseDto")
        OrderResponseDto order,

        @Schema(description = "Outcome kind: CREATED - the order was created by this request, "
                + "REPLAYED_COMPLETED - repeated Idempotency-Key with an already completed saga, "
                + "REPLAYED_CURRENT - repeated Idempotency-Key while the saga is in progress or failed",
                allowableValues = {"CREATED", "REPLAYED_COMPLETED", "REPLAYED_CURRENT"})
        Kind kind) {

    public enum Kind {
        CREATED,
        REPLAYED_COMPLETED,
        REPLAYED_CURRENT
    }
}
