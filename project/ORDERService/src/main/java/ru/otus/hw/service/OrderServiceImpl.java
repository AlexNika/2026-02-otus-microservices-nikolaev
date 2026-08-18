package ru.otus.hw.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientResponseException;
import ru.otus.hw.client.BillingServiceClient;
import ru.otus.hw.client.DeliveryServiceClient;
import ru.otus.hw.client.WarehouseServiceClient;
import ru.otus.hw.dto.DeliveryReservationResponse;
import ru.otus.hw.dto.NotificationEvent;
import ru.otus.hw.dto.OrderCreateDto;
import ru.otus.hw.dto.OrderCreateResult;
import ru.otus.hw.dto.OrderResponseDto;
import ru.otus.hw.dto.ProductReservationListResponseDto;
import ru.otus.hw.dto.ReservationStatus;
import ru.otus.hw.dto.mapper.OrderMapper;
import ru.otus.hw.exception.ErrorCodes;
import ru.otus.hw.exception.IdempotencyConflictException;
import ru.otus.hw.exception.NotFoundException;
import ru.otus.hw.exception.OrderStateConflictException;
import ru.otus.hw.exception.SagaStepException;
import ru.otus.hw.models.IdempotencyKey;
import ru.otus.hw.models.Order;
import ru.otus.hw.models.OrderSagaState;
import ru.otus.hw.models.OrderSagaState.SagaStatus;
import ru.otus.hw.models.OrderSagaState.SagaStep;
import ru.otus.hw.producer.NotificationEventPublisher;
import ru.otus.hw.repository.OrderRepository;
import ru.otus.hw.repository.OrderSagaStateRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Оркестратор саги создания заказа.
 *
 * <p>Внешние вызовы (BILLING/WAREHOUSE/DELIVERY) выполняются ВНЕ открытой транзакции БД:
 * метод {@link #createOrder} не является {@code @Transactional}, каждое сохранение через
 * репозиторий - отдельная короткая транзакция. Состояние саги ведётся в таблице
 * {@code order_saga_state} (1:1 с заказом).
 *
 * <p>Порядок forward-шагов: Billing -> Warehouse -> Delivery, затем confirm-фаза
 * (warehouse.confirm, delivery.confirm; confirm биллинга - no-op). При отказе любого шага
 * компенсация выполняется в обратном порядке только для фактически выполненных шагов:
 * delivery.cancel -> warehouse.cancel -> billing.refund.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private static final int MAX_FAILURE_REASON_LENGTH = 2048;

    private final OrderRepository orderRepository;

    private final OrderSagaStateRepository orderSagaStateRepository;

    private final OrderMapper mapper;

    private final BillingServiceClient billingServiceClient;

    private final WarehouseServiceClient warehouseServiceClient;

    private final DeliveryServiceClient deliveryServiceClient;

    private final NotificationEventPublisher notificationEventPublisher;

    private final IdempotencyService idempotencyService;

    private final OrderCreationService orderCreationService;

    private final TransactionTemplate transactionTemplate;

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
    public List<OrderResponseDto> getOrderByOrderStatus(Order.OrderStatus orderStatus) {
        log.debug("Fetching all orders with defined orderStatus: {}", orderStatus);
        return orderRepository.findByOrderStatus(orderStatus)
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
     * Создаёт заказ и прогоняет сагу резервирования ресурсов (поведение без Idempotency-Key,
     * обратная совместимость).
     */
    @Override
    public OrderResponseDto createOrder(@NonNull OrderCreateDto orderCreateDto) {
        return createOrder(orderCreateDto, null).order();
    }

    /**
     * Создаёт заказ и прогоняет сагу резервирования ресурсов.
     * Не выполняется в единой транзакции: внешние вызовы не должны удерживать транзакцию БД,
     * поэтому каждое сохранение - отдельная короткая транзакция репозитория.
     *
     * <p>При заданном {@code idempotencyKey} повтор того же payload'а никогда не создаёт второй
     * заказ: ключ резервируется в одной транзакции с первым сохранением заказа, успешный ответ
     * сохраняется для replay (201), при провале саги ключ остаётся и повтор возвращает текущее
     * состояние заказа (200). Конфликт payload'а - 409 IDEMPOTENCY_KEY_CONFLICT.
     */
    @Override
    public OrderCreateResult createOrder(@NonNull OrderCreateDto orderCreateDto, UUID idempotencyKey) {
        log.info("Creating order for userId: {}, price: {}, productId: {}, quantity: {}, delivery: {} {}-{}, "
                        + "idempotencyKey: {}",
                orderCreateDto.userId(), orderCreateDto.price(), orderCreateDto.productId(),
                orderCreateDto.quantity(), orderCreateDto.deliveryDate(),
                orderCreateDto.slotStart(), orderCreateDto.slotEnd(), idempotencyKey);

        if (idempotencyKey != null) {
            Optional<IdempotencyKey> existing = idempotencyService.find(idempotencyKey);
            if (existing.isPresent()) {
                return replayExisting(existing.get(), orderCreateDto);
            }
        }

        Order order;
        if (idempotencyKey != null) {
            try {
                order = startOrderWithKey(orderCreateDto, idempotencyKey);
            } catch (DataIntegrityViolationException duplicate) {
                log.info("Idempotency-Key {} was concurrently reserved, switching to replay", idempotencyKey);
                IdempotencyKey concurrent = idempotencyService.find(idempotencyKey).orElseThrow(() -> duplicate);
                return replayExisting(concurrent, orderCreateDto);
            }
        } else {
            order = startOrderWithoutKey(orderCreateDto);
        }

        OrderResponseDto response = runSaga(order, idempotencyKey);
        return new OrderCreateResult(response, OrderCreateResult.Kind.CREATED);
    }

    private @NonNull Order startOrderWithoutKey(@NonNull OrderCreateDto orderCreateDto) {
        Order order = mapper.toEntity(orderCreateDto);
        order.setOrderStatus(Order.OrderStatus.PENDING);
        order = orderRepository.save(order);
        log.info("Order created with PENDING status, id: {}", order.getId());

        orderSagaStateRepository.save(OrderSagaState.builder()
                .order(order)
                .sagaStatus(SagaStatus.STARTED)
                .build());
        log.info("Saga started for order id: {}", order.getId());

        return markProcessing(order);
    }

    private @NonNull Order startOrderWithKey(@NonNull OrderCreateDto orderCreateDto, @NonNull UUID idempotencyKey) {
        String requestHash = idempotencyService.requestHash(orderCreateDto);
        Order order = orderCreationService.createOrderWithKey(orderCreateDto, idempotencyKey, requestHash);
        log.info("Saga started for order id: {}", order.getId());

        return markProcessing(order);
    }

    private @NonNull Order markProcessing(@NonNull Order order) {
        order.setOrderStatus(Order.OrderStatus.PROCESSING);
        order = orderRepository.save(order);
        log.info("Order status changed to PROCESSING, id: {}", order.getId());
        return order;
    }

    private @NonNull OrderCreateResult replayExisting(@NonNull IdempotencyKey entry,
                                                      @NonNull OrderCreateDto orderCreateDto) {
        String requestHash = idempotencyService.requestHash(orderCreateDto);
        boolean samePayload = Objects.equals(entry.getRequestHash(), requestHash)
                && Objects.equals(entry.getUserId(), orderCreateDto.userId());
        if (!samePayload) {
            log.warn("Idempotency-Key conflict: key {} already used with a different payload/userId",
                    entry.getIdempotencyKey());
            throw new IdempotencyConflictException("Idempotency-Key already used with a different request payload");
        }

        if (entry.getResponseStatus() != null && entry.getResponseBody() != null) {
            OrderResponseDto stored = idempotencyService.readStoredResponse(entry);
            log.info("Idempotent replay of completed order: key={}, orderId={}, storedStatus={}",
                    entry.getIdempotencyKey(), stored.id(), entry.getResponseStatus());
            return new OrderCreateResult(stored, OrderCreateResult.Kind.REPLAYED_COMPLETED);
        }

        Order currentOrder = orderRepository.findById(entry.getOrderId())
                .orElseThrow(() -> new NotFoundException("Order with id " + entry.getOrderId() + " not found"));
        log.info("Idempotent replay of in-progress/failed order: key={}, orderId={}, orderStatus={}",
                entry.getIdempotencyKey(), currentOrder.getId(), currentOrder.getOrderStatus());
        return new OrderCreateResult(mapper.toOrderResponseDto(currentOrder),
                OrderCreateResult.Kind.REPLAYED_CURRENT);
    }

    private @NonNull OrderResponseDto runSaga(@NonNull Order order, UUID idempotencyKey) {
        final Long orderId = order.getId();
        final Long userId = order.getUserId();
        final BigDecimal price = order.getPrice();
        final Long productId = order.getProductId();
        final Integer quantity = order.getQuantity();
        final LocalDate deliveryDate = order.getDeliveryDate();
        final LocalTime slotStart = order.getSlotStart();
        final LocalTime slotEnd = order.getSlotEnd();

        boolean billingReserved = false;
        boolean warehouseReserved = false;
        boolean deliveryReserved = false;
        SagaStep currentStep = SagaStep.BILLING_WITHDRAW;
        try {
            SagaStepRetrier.executeWithRetry("BILLING_WITHDRAW",
                    () -> billingServiceClient.withdrawFunds(userId, price, orderId));
            billingReserved = true;
            log.info("BillingService withdrawal successful for order id: {}", orderId);
            transitionSaga(orderId, SagaStatus.BILLING_RESERVED);

            currentStep = SagaStep.WAREHOUSE_RESERVE;
            SagaStepRetrier.executeWithRetry("WAREHOUSE_RESERVE",
                    () -> warehouseServiceClient.reserve(orderId, productId, quantity,
                            warehouseIdempotencyKey(orderId, productId)));
            warehouseReserved = true;
            log.info("WarehouseService reservation successful for order id: {}", orderId);
            transitionSaga(orderId, SagaStatus.WAREHOUSE_RESERVED);

            currentStep = SagaStep.DELIVERY_RESERVE;
            SagaStepRetrier.executeWithRetry("DELIVERY_RESERVE",
                    () -> deliveryServiceClient.reserve(orderId, deliveryDate, slotStart, slotEnd));
            deliveryReserved = true;
            log.info("DeliveryService reservation successful for order id: {}", orderId);
            transitionSaga(orderId, SagaStatus.DELIVERY_RESERVED);

            currentStep = SagaStep.WAREHOUSE_CONFIRM;
            confirmWarehouse(orderId);
            currentStep = SagaStep.DELIVERY_CONFIRM;
            confirmDelivery(orderId);
            transitionSaga(orderId, SagaStatus.CONFIRMED);

            order.setOrderStatus(Order.OrderStatus.PLACED);
            order = finalizeOrderWithNotification(order, "Order placed successfully. Payment confirmed.");
            log.info("Order status changed to PLACED, id: {}", order.getId());

            OrderResponseDto response = mapper.toOrderResponseDto(order);
            if (idempotencyKey != null) {
                idempotencyService.recordSuccess(idempotencyKey, response);
            }
            return response;

        } catch (Exception failure) {
            SagaStatus finalStatus = compensate(order, currentStep, failure,
                    deliveryReserved, warehouseReserved, billingReserved);

            order.setOrderStatus(Order.OrderStatus.FAILED);
            order = finalizeOrderWithNotification(order, "Order processing failed: " + failure.getMessage());
            log.info("Order status changed to FAILED, id: {}", order.getId());

            if (idempotencyKey != null) {
                idempotencyService.recordSagaStatus(idempotencyKey, finalStatus);
            }

            throw asRuntime(failure);
        }
    }

    /**
     * Confirm брони склада с ретраями на транзитные ошибки. Если после исчерпания ретраев
     * confirm так и не ответил успехом (таймаут/потеря ответа), состояние брони уточняется
     * read-запросом: бронь может уже быть CONFIRMED на стороне склада, тогда компенсация не нужна -
     * сага продолжается.
     */
    private void confirmWarehouse(@NonNull Long orderId) {
        try {
            SagaStepRetrier.executeWithRetry("WAREHOUSE_CONFIRM", () ->
                    warehouseServiceClient.confirm(orderId));
        } catch (RuntimeException e) {
            if (isWarehouseReservationConfirmed(orderId)) {
                log.warn("WAREHOUSE_CONFIRM failed after retries, but reservation is CONFIRMED "
                        + "for order id: {} — proceeding without compensation", orderId);
                return;
            }
            throw e;
        }
    }

    /**
     * Confirm брони доставки с ретраями и уточнением состояния брони при неудаче
     * (см. {@link #confirmWarehouse}).
     */
    private void confirmDelivery(@NonNull Long orderId) {
        try {
            SagaStepRetrier.executeWithRetry("DELIVERY_CONFIRM", () -> deliveryServiceClient.confirm(orderId));
        } catch (RuntimeException e) {
            if (isDeliveryReservationConfirmed(orderId)) {
                log.warn("DELIVERY_CONFIRM failed after retries, but reservation is CONFIRMED "
                        + "for order id: {} — proceeding without compensation", orderId);
                return;
            }
            throw e;
        }
    }

    private boolean isWarehouseReservationConfirmed(@NonNull Long orderId) {
        try {
            ProductReservationListResponseDto response = warehouseServiceClient.getReservation(orderId);
            if (response == null || response.reservations() == null || response.reservations().isEmpty()) {
                return false;
            }
            return response.reservations().stream()
                    .allMatch(reservation ->
                            reservation.reservationStatus() == ReservationStatus.CONFIRMED);
        } catch (Exception e) {
            log.warn("Failed to probe warehouse reservation state for order id: {}", orderId, e);
            return false;
        }
    }

    private boolean isDeliveryReservationConfirmed(@NonNull Long orderId) {
        try {
            DeliveryReservationResponse response = deliveryServiceClient.getReservation(orderId);
            return response != null && "CONFIRMED".equals(response.status());
        } catch (Exception e) {
            log.warn("Failed to probe delivery reservation state for order id: {}", orderId, e);
            return false;
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

    private @NonNull SagaStatus compensate(@NonNull Order order, @NonNull SagaStep failedStep,
                                           @NonNull Exception failure, boolean deliveryReserved,
                                           boolean warehouseReserved, boolean billingReserved) {
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
        return finalStatus;
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
     * 400/409 от ресурса при откате означает, что бронь уже CONFIRMED и заказ отменить нельзя -
     * возвращаем конфликт без смены статуса заказа (в т.ч. явно по машинному коду
     * RESERVATION_ALREADY_CONFIRMED склада). Остальные ошибки пробрасываем как есть.
     */
    private @NonNull RuntimeException resourceCancelConflictOrRethrow(Long orderId, @NonNull String resource,
                                                                      @NonNull SagaStepException e) {
        boolean explicitConfirmedCode = ErrorCodes.RESERVATION_ALREADY_CONFIRMED.equals(e.getCode());
        boolean httpConflict = e.getCause() instanceof RestClientResponseException responseException
                && (responseException.getStatusCode().value() == 400
                || responseException.getStatusCode().value() == 409);
        if (explicitConfirmedCode || httpConflict) {
            log.warn("Reservation in {} is final for order id: {}, cancel rejected: {}",
                    resource, orderId, e.getMessage());
            return OrderStateConflictException.resourceNotCancellable(orderId, resource, e.getMessage());
        }
        return e;
    }

    /**
     * Детерминированный строковый idempotencyKey резерва склада для пары (orderId, productId).
     * Формат "order-{orderId}-p{productId}" исключает коллизии и диапазонные ограничения
     * (в отличие от прежнего числового множителя).
     */
    private @NonNull String warehouseIdempotencyKey(@NonNull Long orderId, @NonNull Long productId) {
        return "order-" + orderId + "-p" + productId;
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

    /**
     * Финальное сохранение заказа + запись события уведомления в transactional outbox ОДНОЙ
     * короткой транзакцией: статус заказа и событие фиксируются атомарно, "заказ сохранён,
     * а событие потеряно" (и наоборот) невозможно. Фактическую публикацию в RabbitMQ
     * выполняет scheduled {@code OutboxPublisher}.
     */
    private @NonNull Order finalizeOrderWithNotification(@NonNull Order order, @NonNull String message) {
        Order savedOrder = transactionTemplate.execute(_ -> {
            Order saved = orderRepository.save(order);
            notificationEventPublisher.send(buildNotificationEvent(saved, message));
            return saved;
        });
        return Objects.requireNonNull(savedOrder, "Order save with notification event returned null");
    }

    private NotificationEvent buildNotificationEvent(@NonNull Order order, String message) {
        return NotificationEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .orderId(order.getId())
                .userId(order.getUserId())
                .price(order.getPrice())
                .status(order.getOrderStatus().name())
                .message(message)
                .timestamp(Instant.now())
                .build();
    }
}
