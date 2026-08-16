package ru.otus.hw.dto;

/**
 * Результат создания заказа через POST /api/v1/order с учётом обработки Idempotency-Key.
 *
 * <p>{@link Kind#CREATED} — заказ создан впервые (201);
 * {@link Kind#REPLAYED_COMPLETED} — повтор ключа, сага уже завершена успешно: возвращается
 * сохранённый ответ (201 + тот же заказ);
 * {@link Kind#REPLAYED_CURRENT} — повтор ключа, сага в процессе или завершилась провалом:
 * возвращается текущее состояние заказа (200), второй заказ не создаётся.
 */
public record OrderCreateResult(OrderResponseDto order, Kind kind) {

    public enum Kind {
        CREATED,
        REPLAYED_COMPLETED,
        REPLAYED_CURRENT
    }
}
