package ru.otus.hw.service;

import org.jspecify.annotations.NonNull;
import ru.otus.hw.dto.OrderCreateDto;
import ru.otus.hw.models.Order;

import java.util.UUID;

/**
 * Транзакционный старт создания заказа. Заказ и запись Idempotency-Key фиксируются ОДНОЙ
 * транзакцией: конфликт уникального ключа (повторный запрос) откатывает и создание заказа,
 * после чего вызывающий код переходит на replay-путь вместо создания дубликата.
 */
public interface OrderCreationService {

    /**
     * Создаёт заказ в статусе PENDING, резервирует ключ идемпотентности и запускает сагу
     * (запись STARTED) - одна транзакция. Немедленная фиксация записи ключа гарантирует,
     * что нарушение уникальности будет обнаружено до коммита и откатит весь блок.
     * {@code userId} берётся из JWT-принципала, а не из тела запроса.
     */
    Order createOrderWithKey(@NonNull OrderCreateDto orderCreateDto, @NonNull Long userId,
                             @NonNull UUID idempotencyKey, @NonNull String requestHash);
}
