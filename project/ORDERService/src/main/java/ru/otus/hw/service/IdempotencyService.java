package ru.otus.hw.service;

import ru.otus.hw.dto.OrderCreateDto;
import ru.otus.hw.dto.OrderResponseDto;
import ru.otus.hw.models.IdempotencyKey;
import ru.otus.hw.models.OrderSagaState.SagaStatus;

import java.util.Optional;
import java.util.UUID;

/**
 * Работа с ключами идемпотентности POST /api/v1/order: хеширование payload'а,
 * учёт результата саги по ключу и TTL-очистка.
 */
public interface IdempotencyService {

    Optional<IdempotencyKey> find(UUID idempotencyKey);

    /**
     * SHA-256 канонического JSON payload'а (стабильный порядок полей).
     */
    String requestHash(OrderCreateDto orderCreateDto);

    /**
     * Новая запись ключа со сроком жизни {@code app.idempotency.ttl}.
     */
    IdempotencyKey newEntry(UUID idempotencyKey, Long userId, String requestHash, Long orderId);

    /**
     * Успешный итог саги: сохраняет ответ (201 + JSON заказа) для replay.
     */
    void recordSuccess(UUID idempotencyKey, OrderResponseDto response);

    /**
     * Провал саги: обновляет saga_status, ключ остаётся - повтор запроса не создаёт второй заказ.
     */
    void recordSagaStatus(UUID idempotencyKey, SagaStatus sagaStatus);

    /**
     * Десериализует сохранённый успешный ответ.
     */
    OrderResponseDto readStoredResponse(IdempotencyKey entry);

    /**
     * Удаляет истёкшие ключи.
     */
    void cleanupExpiredKeys();
}
