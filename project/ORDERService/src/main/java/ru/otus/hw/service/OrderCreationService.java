package ru.otus.hw.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.otus.hw.dto.OrderCreateDto;
import ru.otus.hw.dto.mapper.OrderMapper;
import ru.otus.hw.models.Order;
import ru.otus.hw.models.OrderSagaState;
import ru.otus.hw.repository.IdempotencyKeyRepository;
import ru.otus.hw.repository.OrderRepository;
import ru.otus.hw.repository.OrderSagaStateRepository;

import java.util.UUID;

/**
 * Транзакционный старт создания заказа. Заказ и запись Idempotency-Key фиксируются ОДНОЙ
 * транзакцией: конфликт уникального ключа (повторный запрос) откатывает и создание заказа,
 * после чего вызывающий код переходит на replay-путь вместо создания дубликата.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderCreationService {

    private final OrderRepository orderRepository;

    private final OrderSagaStateRepository orderSagaStateRepository;

    private final IdempotencyKeyRepository idempotencyKeyRepository;

    private final OrderMapper mapper;

    private final IdempotencyService idempotencyService;

    /**
     * Создаёт заказ в статусе PENDING, резервирует ключ идемпотентности и запускает сагу
     * (запись STARTED) - одна транзакция. {@code saveAndFlush} гарантирует, что нарушение
     * уникальности ключа будет обнаружено до коммита и откатит весь блок.
     */
    @Transactional
    public Order createOrderWithKey(@NonNull OrderCreateDto orderCreateDto, @NonNull UUID idempotencyKey,
                                    @NonNull String requestHash) {
        Order order = mapper.toEntity(orderCreateDto);
        order.setOrderStatus(Order.OrderStatus.PENDING);
        order = orderRepository.save(order);

        idempotencyKeyRepository.saveAndFlush(
                idempotencyService.newEntry(idempotencyKey, orderCreateDto.userId(), requestHash, order.getId()));

        orderSagaStateRepository.save(OrderSagaState.builder()
                .order(order)
                .sagaStatus(OrderSagaState.SagaStatus.STARTED)
                .build());
        log.info("Order created with PENDING status and idempotency key reserved, orderId: {}, key: {}",
                order.getId(), idempotencyKey);
        return order;
    }
}
