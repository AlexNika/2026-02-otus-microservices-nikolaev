package ru.otus.hw.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientResponseException;
import ru.otus.hw.client.BillingServiceClient;
import ru.otus.hw.client.DeliveryServiceClient;
import ru.otus.hw.client.WarehouseServiceClient;
import ru.otus.hw.dto.NotificationEvent;
import ru.otus.hw.dto.OrderCreateDto;
import ru.otus.hw.dto.OrderResponseDto;
import ru.otus.hw.dto.mapper.OrderMapper;
import ru.otus.hw.exception.NotFoundException;
import ru.otus.hw.exception.OrderStateConflictException;
import ru.otus.hw.exception.SagaStepException;
import ru.otus.hw.models.Order;
import ru.otus.hw.models.OrderSagaState;
import ru.otus.hw.models.OrderSagaState.SagaStatus;
import ru.otus.hw.models.OrderSagaState.SagaStep;
import ru.otus.hw.producer.NotificationEventPublisher;
import ru.otus.hw.repository.OrderRepository;
import ru.otus.hw.repository.OrderSagaStateRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Оркестратор саги создания заказа.
 *
 * <p>Внешние вызовы (BILLING/WAREHOUSE/DELIVERY) выполняются ВНЕ открытой транзакции БД:
 * метод {@link #createOrder} не является {@code @Transactional}, каждое сохранение через
 * репозиторий — отдельная короткая транзакция. Состояние саги ведётся в таблице
 * {@code order_saga_state} (1:1 с заказом).
 *
 * <p>Порядок forward-шагов: Billing -> Warehouse -> Delivery, затем confirm-фаза
 * (warehouse.confirm, delivery.confirm; confirm биллинга — no-op). При отказе любого шага
 * компенсация выполняется в обратном порядке только для фактически выполненных шагов:
 * delivery.cancel -> warehouse.cancel -> billing.refund.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    /**
     * Множитель для детерминированного idempotencyKey резерва склада.
     * Ключ уникален для пары (orderId, productId) при productId < {@value}.
     * Это Long-представление формулы "order-{orderId}-p{productId}": wire-контракт склада
     * ожидает числовой idempotencyKey.
     */
    private static final long IDEMPOTENCY_KEY_MULTIPLIER = 1_000_000L;

    private static final int MAX_FAILURE_REASON_LENGTH = 2048;

    private final OrderRepository orderRepository;

    private final OrderSagaStateRepository orderSagaStateRepository;

    private final OrderMapper mapper;

    private final BillingServiceClient billingServiceClient;

    private final WarehouseServiceClient warehouseServiceClient;

    private final DeliveryServiceClient deliveryServiceClient;

    private final NotificationEventPublisher notificationEventPublisher;

    @Override
    @Transactional(readOnly = true)
    public Optional<OrderResponseDto> findOrderById(Long id) {
        log.debug("Searching for order with id: {}", id);
        return orderRepository.findById(id)
                .map(order -> {
                    log.info("Order with id: {} found", id);
                    return mapper.toOrderResponseDto(order);
                });
    }

    @Override
    @Transactional(readOnly = true)
    public OrderResponseDto getOrderById(Long id) {
        return findOrderById(id)
                .orElseThrow(() -> new NotFoundException("Order with id " + id + " not found"));
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderResponseDto> getAllOrders() {
        log.debug("Fetching all orders");
        return orderRepository.findAll()
                .stream()
                .map(mapper::toOrderResponseDto)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderResponseDto> getOrderByUserId(Long userId) {
        log.debug("Fetching all orders for userId: {}", userId);
        return orderRepository.findByUserId(userId)
                .stream()
                .map(mapper::toOrderResponseDto)
                .toList();
    }

    /**
     * Создаёт заказ и прогоняет сагу резервирования ресурсов.
     * Не выполняется в единой транзакции: внешние вызовы не должны удерживать транзакцию БД,
     * поэтому каждое сохранение — отдельная короткая транзакция репозитория.
     */
    @Override
    public OrderResponseDto createOrder(@NonNull OrderCreateDto orderCreateDto) {
        log.info("Creating order for userId: {}, price: {}, productId: {}, quantity: {}, delivery: {} {}-{}",
                orderCreateDto.userId(), orderCreateDto.price(), orderCreateDto.productId(),
                orderCreateDto.quantity(), orderCreateDto.deliveryDate(),
                orderCreateDto.slotStart(), orderCreateDto.slotEnd());

        Order order = mapper.toEntity(orderCreateDto);
        order.setOrderStatus(Order.OrderStatus.PENDING);
        order = orderRepository.save(order);
        log.info("Order created with PENDING status, id: {}", order.getId());

        orderSagaStateRepository.save(OrderSagaState.builder()
                .order(order)
                .sagaStatus(SagaStatus.STARTED)
                .build());
        log.info("Saga started for order id: {}", order.getId());

        order.setOrderStatus(Order.OrderStatus.PROCESSING);
        order = orderRepository.save(order);
        log.info("Order status changed to PROCESSING, id: {}", order.getId());

        boolean billingReserved = false;
        boolean warehouseReserved = false;
        boolean deliveryReserved = false;
        SagaStep currentStep = SagaStep.BILLING_WITHDRAW;
        try {
            billingServiceClient.withdrawFunds(order.getUserId(), order.getPrice(), order.getId());
            billingReserved = true;
            log.info("BillingService withdrawal successful for order id: {}", order.getId());
            transitionSaga(order.getId(), SagaStatus.BILLING_RESERVED);

            currentStep = SagaStep.WAREHOUSE_RESERVE;
            warehouseServiceClient.reserve(order.getId(), order.getProductId(), order.getQuantity(),
                    warehouseIdempotencyKey(order.getId(), order.getProductId()));
            warehouseReserved = true;
            log.info("WarehouseService reservation successful for order id: {}", order.getId());
            transitionSaga(order.getId(), SagaStatus.WAREHOUSE_RESERVED);

            currentStep = SagaStep.DELIVERY_RESERVE;
            deliveryServiceClient.reserve(order.getId(), order.getDeliveryDate(),
                    order.getSlotStart(), order.getSlotEnd());
            deliveryReserved = true;
            log.info("DeliveryService reservation successful for order id: {}", order.getId());
            transitionSaga(order.getId(), SagaStatus.DELIVERY_RESERVED);

            currentStep = SagaStep.WAREHOUSE_CONFIRM;
            warehouseServiceClient.confirm(order.getId());
            currentStep = SagaStep.DELIVERY_CONFIRM;
            deliveryServiceClient.confirm(order.getId());
            transitionSaga(order.getId(), SagaStatus.CONFIRMED);

            order.setOrderStatus(Order.OrderStatus.PLACED);
            order = orderRepository.save(order);
            log.info("Order status changed to PLACED, id: {}", order.getId());

            notificationEventPublisher.send(buildNotificationEvent(
                    order, "Order placed successfully. Payment confirmed."));

            return mapper.toOrderResponseDto(order);

        } catch (Exception failure) {
            compensate(order, currentStep, failure, deliveryReserved, warehouseReserved, billingReserved);

            order.setOrderStatus(Order.OrderStatus.FAILED);
            order = orderRepository.save(order);
            log.info("Order status changed to FAILED, id: {}", order.getId());

            notificationEventPublisher.send(buildNotificationEvent(
                    order, "Order processing failed: " + failure.getMessage()));

            throw asRuntime(failure);
        }
    }

    /**
     * Отмена оформленного заказа: refund биллинга + откат брони склада + откат брони доставки
     * (резервы ожидаются в статусе RESERVED). Если ресурс уже CONFIRMED (после успешной саги
     * это всегда так), соответствующий cancel вернёт 400/409, заказ НЕ помечается CANCELED и
     * возвращается конфликт: после confirm ресурсы финальны и откат невозможен.
     */
    @Override
    public OrderResponseDto cancelOrder(Long orderId) {
        log.info("Cancelling order with id: {}", orderId);

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new NotFoundException("Order not found with id: " + orderId));
        log.debug("Order with id: {} found, current status: {}", orderId, order.getOrderStatus());

        if (order.getOrderStatus() != Order.OrderStatus.PLACED) {
            log.warn("Only PLACED orders can be canceled, current status: {}", order.getOrderStatus());
            throw OrderStateConflictException.cancelNotAllowed(orderId, order.getOrderStatus().name());
        }

        billingServiceClient.refundFunds(order.getUserId(), order.getPrice(), order.getId());
        log.info("BillingService refund successful for order id: {}", orderId);

        try {
            warehouseServiceClient.cancel(orderId);
            log.info("WarehouseService reservation cancelled for order id: {}", orderId);
        } catch (SagaStepException e) {
            throw resourceCancelConflictOrRethrow(orderId, "WAREHOUSEService", e);
        }

        try {
            deliveryServiceClient.cancel(orderId);
            log.info("DeliveryService reservation cancelled for order id: {}", orderId);
        } catch (SagaStepException e) {
            throw resourceCancelConflictOrRethrow(orderId, "DELIVERYService", e);
        }

        order.setOrderStatus(Order.OrderStatus.CANCELED);
        order = orderRepository.save(order);
        log.info("Order status changed to CANCELED, id: {}", orderId);

        return mapper.toOrderResponseDto(order);
    }

    private void compensate(@NonNull Order order, @NonNull SagaStep failedStep,
                            @NonNull Exception failure, boolean deliveryReserved, boolean warehouseReserved,
                            boolean billingReserved) {
        log.error("Saga step {} failed for order id: {}, starting compensation", failedStep, order.getId(), failure);

        updateSaga(order.getId(), saga -> {
            saga.setSagaStatus(SagaStatus.COMPENSATING);
            saga.setFailureStep(failedStep);
            saga.setFailureReason(abbreviate(failure.getMessage()));
        });

        boolean compensationFailed = false;

        if (deliveryReserved) {
            try {
                deliveryServiceClient.cancel(order.getId());
                log.info("Compensation: delivery reservation cancelled for order id: {}", order.getId());
            } catch (Exception e) {
                compensationFailed = true;
                log.error("Compensation failed: delivery cancel for order id: {}", order.getId(), e);
            }
        }

        if (warehouseReserved) {
            try {
                warehouseServiceClient.cancel(order.getId());
                log.info("Compensation: warehouse reservation cancelled for order id: {}", order.getId());
            } catch (Exception e) {
                compensationFailed = true;
                log.error("Compensation failed: warehouse cancel for order id: {}", order.getId(), e);
            }
        }

        if (billingReserved) {
            try {
                billingServiceClient.refundFunds(order.getUserId(), order.getPrice(), order.getId());
                log.info("Compensation: billing refund done for order id: {}", order.getId());
            } catch (Exception e) {
                compensationFailed = true;
                log.error("Compensation failed: billing refund for order id: {}", order.getId(), e);
            }
        }

        SagaStatus finalStatus = compensationFailed ? SagaStatus.COMPENSATION_FAILED : SagaStatus.COMPENSATED;
        updateSaga(order.getId(), saga -> saga.setSagaStatus(finalStatus));
        log.info("Saga for order id: {} finished compensation with status: {}", order.getId(), finalStatus);
    }

    /**
     * Обновляет состояние саги короткой транзакцией. Каждый раз читает актуальную строку,
     * чтобы не переиспользовать detached-сущность с устаревшим {@code version}.
     */
    private void updateSaga(@NonNull Long orderId, @NonNull Consumer<OrderSagaState> modifier) {
        OrderSagaState saga = orderSagaStateRepository.findByOrderId(orderId)
                .orElseThrow(() -> new IllegalStateException("Saga state not found for order id: " + orderId));
        modifier.accept(saga);
        orderSagaStateRepository.save(saga);
    }

    private void transitionSaga(@NonNull Long orderId, @NonNull SagaStatus status) {
        updateSaga(orderId, saga -> saga.setSagaStatus(status));
        log.debug("Saga transitioned to {} for order id: {}", status, orderId);
    }

    /**
     * 400/409 от ресурса при откате означает, что бронь уже CONFIRMED и заказ отменить нельзя —
     * возвращаем конфликт без смены статуса заказа. Остальные ошибки пробрасываем как есть.
     */
    private @NonNull RuntimeException resourceCancelConflictOrRethrow(Long orderId, @NonNull String resource,
                                                                      @NonNull SagaStepException e) {
        if (e.getCause() instanceof RestClientResponseException responseException
                && (responseException.getStatusCode().value() == 400
                || responseException.getStatusCode().value() == 409)) {
            log.warn("Reservation in {} is final for order id: {}, cancel rejected: {}",
                    resource, orderId, e.getMessage());
            return OrderStateConflictException.resourceNotCancellable(orderId, resource, e.getMessage());
        }
        return e;
    }

    private @NonNull Long warehouseIdempotencyKey(@NonNull Long orderId, @NonNull Long productId) {
        return orderId * IDEMPOTENCY_KEY_MULTIPLIER + productId;
    }

    private static RuntimeException asRuntime(@NonNull Exception failure) {
        if (failure instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new IllegalStateException("Unexpected checked exception during saga execution", failure);
    }

    private static String abbreviate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= MAX_FAILURE_REASON_LENGTH
                ? message
                : message.substring(0, MAX_FAILURE_REASON_LENGTH);
    }

    private NotificationEvent buildNotificationEvent(@NonNull Order order, String message) {
        return NotificationEvent.builder()
                .orderId(order.getId())
                .userId(order.getUserId())
                .price(order.getPrice())
                .status(order.getOrderStatus().name())
                .message(message)
                .timestamp(Instant.now())
                .build();
    }
}
