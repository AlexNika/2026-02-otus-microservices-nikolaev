package ru.otus.hw.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import ru.otus.hw.client.BillingServiceClient;
import ru.otus.hw.client.DeliveryServiceClient;
import ru.otus.hw.client.WarehouseServiceClient;
import ru.otus.hw.config.properties.SagaRecoveryProperties;
import ru.otus.hw.dto.DeliveryReservationResponse;
import ru.otus.hw.dto.NotificationEvent;
import ru.otus.hw.dto.ProductReservationListResponseDto;
import ru.otus.hw.dto.ProductReservationResponseDto;
import ru.otus.hw.dto.ReservationStatus;
import ru.otus.hw.metrics.OrderBusinessMetrics;
import ru.otus.hw.metrics.SagaMetrics;
import ru.otus.hw.models.Order;
import ru.otus.hw.models.OrderSagaState;
import ru.otus.hw.models.OrderSagaState.SagaStatus;
import ru.otus.hw.models.OrderSagaState.SagaStep;
import ru.otus.hw.producer.NotificationEventPublisher;
import ru.otus.hw.repository.OrderRepository;
import ru.otus.hw.repository.OrderSagaStateRepository;
import ru.otus.hw.tracing.W3CTraceContextAdapter;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Recovery сага-оркестратора.
 *
 * <p>Подбирает саги в промежуточных статусах без прогресса дольше
 * {@code app.saga.recovery.stale-after} (крах ORDERService между шагами) и доводит их до
 * терминального состояния, восстанавливая фактическое состояние ресурсными read-запросами
 * (billing withdraw-status, warehouse/delivery getReservation):
 * <ul>
 *   <li>все forward-шаги фактически выполнены → confirm-фаза → CONFIRMED + заказ PLACED;</li>
 *   <li>иначе → компенсации в обратном порядке от последнего фактически выполненного шага
 *       → COMPENSATED + заказ FAILED;</li>
 *   <li>COMPENSATING → продолжение компенсаций по фактическому состоянию;</li>
 *   <li>отдельный кейс: сага CONFIRMED, но заказ не PLACED (крах между transition и финальным
 *       save) → достраивается PLACED + запись уведомления в outbox.</li>
 * </ul>
 *
 * <p>Защита от конкурентного recovery - optimistic {@code @Version} на строке саги;
 * COMPENSATION_FAILED остаётся для ручного разбирательства.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SagaRecoveryService {

    private static final List<SagaStatus> RECOVERABLE_STATUSES = List.of(
            SagaStatus.STARTED,
            SagaStatus.BILLING_RESERVED,
            SagaStatus.WAREHOUSE_RESERVED,
            SagaStatus.DELIVERY_RESERVED,
            SagaStatus.COMPENSATING);

    private static final List<SagaStatus> CONFIRMED_STATUS = List.of(SagaStatus.CONFIRMED);

    private final OrderSagaStateRepository orderSagaStateRepository;

    private final OrderRepository orderRepository;

    private final BillingServiceClient billingServiceClient;

    private final WarehouseServiceClient warehouseServiceClient;

    private final DeliveryServiceClient deliveryServiceClient;

    private final NotificationEventPublisher notificationEventPublisher;

    private final SagaRecoveryProperties sagaRecoveryProperties;

    private final TransactionTemplate transactionTemplate;

    private final SagaMetrics sagaMetrics;

    private final OrderBusinessMetrics orderBusinessMetrics;

    private final W3CTraceContextAdapter traceContextAdapter;

    @Scheduled(fixedDelayString = "${app.saga.recovery.interval:60000}")
    public void recoverStaleSagas() {
        LocalDateTime staleBefore = LocalDateTime.now().minus(sagaRecoveryProperties.getStaleAfter());

        List<OrderSagaState> staleSagas = orderSagaStateRepository.findStaleSagas(RECOVERABLE_STATUSES, staleBefore);
        for (OrderSagaState saga : staleSagas) {
            try {
                sagaMetrics.recoveryTriggered(SagaMetrics.RECOVERY_KIND_STALE);
                recoverSaga(saga);
            } catch (Exception e) {
                sagaMetrics.recoveryFailure();
                log.error("Saga recovery failed for order id: {}, will retry on the next run",
                        orderIdOf(saga));
            }
        }

        List<OrderSagaState> confirmedSagas = orderSagaStateRepository.findStaleSagas(CONFIRMED_STATUS, staleBefore);
        for (OrderSagaState saga : confirmedSagas) {
            try {
                completeConfirmedOrder(saga);
            } catch (Exception e) {
                sagaMetrics.recoveryFailure();
                log.error("Saga recovery (CONFIRMED finalization) failed for order id: {}, will retry on the next run",
                        orderIdOf(saga));
            }
        }
    }

    private void recoverSaga(@NonNull OrderSagaState saga) {
        try (W3CTraceContextAdapter.Scope ignored = traceContextAdapter.open(Map.of(), "saga.recovery")) {
            recoverSagaInternal(saga);
        }
    }

    private void recoverSagaInternal(@NonNull OrderSagaState saga) {
        Order order = saga.getOrder();
        Long orderId = order.getId();
        log.info("Saga recovery: probing actual state for order id: {}, sagaStatus: {}",
                orderId, saga.getSagaStatus());

        boolean billingWithdrawn;
        boolean warehouseReserved;
        boolean deliveryReserved;
        try {
            billingWithdrawn = billingServiceClient.getWithdrawStatus(orderId).withdrawn();
            warehouseReserved = probeWarehouseReserved(orderId);
            deliveryReserved = probeDeliveryReserved(orderId);
        } catch (Exception e) {
            log.error("Saga recovery: downstream probe failed for order id: {}, will retry on the next run",
                    orderId);
            return;
        }
        log.info("Saga recovery actual state for order id: {}: billing={}, warehouse={}, delivery={}",
                orderId, billingWithdrawn, warehouseReserved, deliveryReserved);

        if (saga.getSagaStatus() == SagaStatus.COMPENSATING) {
            compensateActual(saga, order, billingWithdrawn, warehouseReserved, deliveryReserved);
            return;
        }

        if (billingWithdrawn && warehouseReserved && deliveryReserved) {
            completeConfirmPhase(saga, order);
        } else {
            compensateActual(saga, order, billingWithdrawn, warehouseReserved, deliveryReserved);
        }
    }

    private boolean probeWarehouseReserved(@NonNull Long orderId) {
        try {
            ProductReservationListResponseDto response = warehouseServiceClient.getReservation(orderId);
            if (response == null || response.reservations() == null || response.reservations().isEmpty()) {
                return false;
            }
            return response.reservations().stream().allMatch(SagaRecoveryService::isWarehouseActive);
        } catch (Exception e) {
            if (isNotFound(e)) {
                return false;
            }
            throw e;
        }
    }

    private static boolean isWarehouseActive(@NonNull ProductReservationResponseDto reservation) {
        ReservationStatus status = reservation.reservationStatus();
        return status == ReservationStatus.RESERVED || status == ReservationStatus.CONFIRMED;
    }

    private boolean probeDeliveryReserved(@NonNull Long orderId) {
        try {
            DeliveryReservationResponse response = deliveryServiceClient.getReservation(orderId);
            if (response == null || response.status() == null) {
                return false;
            }
            return "RESERVED".equals(response.status()) || "CONFIRMED".equals(response.status());
        } catch (Exception e) {
            if (isNotFound(e)) {
                return false;
            }
            throw e;
        }
    }

    private static boolean isNotFound(@NonNull Exception e) {
        Throwable current = e;
        while (current != null) {
            if (current instanceof org.springframework.web.client.HttpClientErrorException.NotFound) {
                return true;
            }
            if (current instanceof org.springframework.web.client.RestClientResponseException response
                    && response.getStatusCode().value() == 404) {
                return true;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return false;
    }

    private void completeConfirmPhase(@NonNull OrderSagaState saga, @NonNull Order order) {
        Long orderId = order.getId();
        log.info("Saga recovery: all forward steps done for order id: {}, running confirm phase", orderId);
        warehouseServiceClient.confirm(orderId);
        deliveryServiceClient.confirm(orderId);
        transitionSaga(orderId, SagaStatus.CONFIRMED);
        completePlacedOrder(order);
        sagaMetrics.sagaCompleted(SagaMetrics.OUTCOME_PLACED, sagaDuration(saga));
    }

    /**
     * Сага CONFIRMED, но заказ не PLACED (крах между transitionSaga(CONFIRMED) и финальным save).
     */
    private void completeConfirmedOrder(@NonNull OrderSagaState saga) {
        Order order = saga.getOrder();
        if (order.getOrderStatus() == Order.OrderStatus.PLACED) {
            return;
        }
        sagaMetrics.recoveryTriggered(SagaMetrics.RECOVERY_KIND_CONFIRMED_FINALIZATION);
        log.warn("Saga recovery: saga CONFIRMED but order not PLACED - completing. Order id: {}, orderStatus: {}",
                order.getId(), order.getOrderStatus());
        completePlacedOrder(order);
        sagaMetrics.sagaCompleted(SagaMetrics.OUTCOME_PLACED, sagaDuration(saga));
    }

    private void completePlacedOrder(@NonNull Order order) {
        order.setOrderStatus(Order.OrderStatus.PLACED);
        Order saved = finalizeWithNotification(order, "Order placed successfully. Payment confirmed.");
        log.info("Saga recovery: order id: {} marked PLACED", saved.getId());
    }

    private void compensateActual(@NonNull OrderSagaState saga, @NonNull Order order, boolean billingWithdrawn,
                                  boolean warehouseReserved, boolean deliveryReserved) {
        Long orderId = order.getId();
        log.info("Saga recovery: compensating order id: {} from actual state", orderId);

        boolean compensationFailed = false;

        if (deliveryReserved) {
            try {
                deliveryServiceClient.cancel(orderId);
                sagaMetrics.compensation(SagaStep.DELIVERY_CANCEL);
                log.info("Saga recovery compensation: delivery cancelled for order id: {}", orderId);
            } catch (Exception e) {
                compensationFailed = true;
                log.error("Saga recovery compensation failed: delivery cancel for order id: {}. Reason: {}", orderId, e.getMessage());
            }
        }
        if (warehouseReserved) {
            try {
                warehouseServiceClient.cancel(orderId);
                sagaMetrics.compensation(SagaStep.WAREHOUSE_CANCEL);
                log.info("Saga recovery compensation: warehouse released for order id: {}", orderId);
            } catch (Exception e) {
                compensationFailed = true;
                log.error("Saga recovery compensation failed: warehouse cancel for order id: {}. Reason: {}", orderId, e.getMessage());
            }
        }
        if (billingWithdrawn) {
            try {
                billingServiceClient.refundFunds(order.getUserId(), order.getPrice(), orderId);
                sagaMetrics.compensation(SagaStep.BILLING_REFUND);
                log.info("Saga recovery compensation: billing refunded for order id: {}", orderId);
            } catch (Exception e) {
                compensationFailed = true;
                log.error("Saga recovery compensation failed: billing refund for order id: {}. Reason: {}", orderId, e.getMessage());
            }
        }

        SagaStatus finalStatus = compensationFailed ? SagaStatus.COMPENSATION_FAILED : SagaStatus.COMPENSATED;
        transitionSaga(orderId, finalStatus);

        order.setOrderStatus(Order.OrderStatus.FAILED);
        finalizeWithNotification(order, "Order processing failed: saga recovered with compensation");
        log.info("Saga recovery: order id: {} marked FAILED with saga status: {}", orderId, finalStatus);
        sagaMetrics.sagaCompleted(finalStatus == SagaStatus.COMPENSATED
                        ? SagaMetrics.OUTCOME_COMPENSATED
                        : SagaMetrics.OUTCOME_COMPENSATION_FAILED,
                sagaDuration(saga));

        if (compensationFailed) {
            log.error("Saga recovery: order id: {} left in COMPENSATION_FAILED for manual resolution", orderId);
        }
    }

    /**
     * Полная длительность саги для метрики на recovery-пути: от создания записи саги
     * (включает простой упавшего пода).
     */
    private static @Nullable Duration sagaDuration(@NonNull OrderSagaState saga) {
        LocalDateTime created = saga.getCreated();
        if (created == null) {
            return null;
        }
        return Duration.between(created, LocalDateTime.now());
    }

    private @NonNull Order finalizeWithNotification(@NonNull Order order, @NonNull String message) {
        Order savedOrder = transactionTemplate.execute(status -> {
            Order saved = orderRepository.save(order);
            notificationEventPublisher.send(buildNotificationEvent(saved, message));
            return saved;
        });
        Order saved = Objects.requireNonNull(savedOrder, "Order save with notification event returned null");
        orderBusinessMetrics.orderTerminal(saved.getOrderStatus(), saved.getPrice());
        return saved;
    }

    private void transitionSaga(@NonNull Long orderId, @NonNull SagaStatus status) {
        updateSaga(orderId, saga -> saga.setSagaStatus(status));
        log.debug("Saga recovery transitioned to {} for order id: {}", status, orderId);
    }

    private void updateSaga(@NonNull Long orderId, @NonNull Consumer<OrderSagaState> modifier) {
        OrderSagaState saga = orderSagaStateRepository.findByOrderId(orderId)
                .orElseThrow(() -> new IllegalStateException("Saga state not found for order id: " + orderId));
        modifier.accept(saga);
        orderSagaStateRepository.save(saga);
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

    private @Nullable Long orderIdOf(@NonNull OrderSagaState saga) {
        return saga.getOrder() != null ? saga.getOrder().getId() : null;
    }
}
