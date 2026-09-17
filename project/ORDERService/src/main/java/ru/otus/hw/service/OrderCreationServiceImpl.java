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

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderCreationServiceImpl implements OrderCreationService {

    private final OrderRepository orderRepository;

    private final OrderSagaStateRepository orderSagaStateRepository;

    private final IdempotencyKeyRepository idempotencyKeyRepository;

    private final OrderMapper mapper;

    private final IdempotencyService idempotencyService;

    @Override
    @Transactional
    public Order createOrderWithKey(@NonNull OrderCreateDto orderCreateDto, @NonNull Long userId,
                                    @NonNull UUID idempotencyKey, @NonNull String requestHash) {
        Order order = mapper.toEntity(orderCreateDto);
        order.setUserId(userId);
        order.setOrderStatus(Order.OrderStatus.PENDING);
        order = orderRepository.save(order);

        idempotencyKeyRepository.saveAndFlush(
                idempotencyService.newEntry(idempotencyKey, userId, requestHash, order.getId()));

        orderSagaStateRepository.save(OrderSagaState.builder()
                .order(order)
                .sagaStatus(OrderSagaState.SagaStatus.STARTED)
                .build());
        log.info("Order created with PENDING status and idempotency key reserved, orderId: {}, key: {}",
                order.getId(), idempotencyKey);
        return order;
    }
}
