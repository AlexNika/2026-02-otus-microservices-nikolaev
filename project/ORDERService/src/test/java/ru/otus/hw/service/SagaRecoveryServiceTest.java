package ru.otus.hw.service;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatusCode;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;
import ru.otus.hw.client.BillingServiceClient;
import ru.otus.hw.client.DeliveryServiceClient;
import ru.otus.hw.client.WarehouseServiceClient;
import ru.otus.hw.config.properties.SagaRecoveryProperties;
import ru.otus.hw.dto.DeliveryReservationResponse;
import ru.otus.hw.dto.NotificationEvent;
import ru.otus.hw.dto.ProductReservationListResponseDto;
import ru.otus.hw.dto.ProductReservationResponseDto;
import ru.otus.hw.dto.ReservationStatus;
import ru.otus.hw.dto.WithdrawStatusDto;
import ru.otus.hw.exception.BillingServiceException;
import ru.otus.hw.exception.DeliveryServiceException;
import ru.otus.hw.exception.WarehouseServiceException;
import ru.otus.hw.models.Order;
import ru.otus.hw.models.OrderSagaState;
import ru.otus.hw.models.OrderSagaState.SagaStatus;
import ru.otus.hw.models.OrderSagaState.SagaStep;
import ru.otus.hw.producer.NotificationEventPublisher;
import ru.otus.hw.repository.OrderRepository;
import ru.otus.hw.repository.OrderSagaStateRepository;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Recovery сага-оркестратора: сценарии краха между шагами,<br>
 * Краха в COMPENSATING и кейс "CONFIRMED без PLACED".
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SagaRecoveryServiceTest {

    private static final Long ORDER_ID = 100L;

    private static final Long USER_ID = 7L;

    private static final BigDecimal PRICE = new BigDecimal("250.00");

    @Mock
    private OrderSagaStateRepository orderSagaStateRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private BillingServiceClient billingServiceClient;

    @Mock
    private WarehouseServiceClient warehouseServiceClient;

    @Mock
    private DeliveryServiceClient deliveryServiceClient;

    @Mock
    private NotificationEventPublisher notificationEventPublisher;

    @Mock
    private SagaRecoveryProperties sagaRecoveryProperties;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private ru.otus.hw.metrics.SagaMetrics sagaMetrics;

    @Mock
    private ru.otus.hw.metrics.OrderBusinessMetrics orderBusinessMetrics;

    @Mock
    private ru.otus.hw.tracing.W3CTraceContextAdapter traceContextAdapter;

    @InjectMocks
    private SagaRecoveryService sagaRecoveryService;

    private @NonNull Order orderWithStatus(Order.OrderStatus status) {
        Order order = Order.builder()
                .userId(USER_ID)
                .price(PRICE)
                .productId(11L)
                .quantity(3)
                .deliveryDate(LocalDate.now().plusDays(1))
                .slotStart(LocalTime.of(10, 0))
                .slotEnd(LocalTime.of(12, 0))
                .orderStatus(status)
                .build();
        order.setId(ORDER_ID);
        return order;
    }

    private @NonNull OrderSagaState saga(SagaStatus status, Order order) {
        OrderSagaState saga = OrderSagaState.builder()
                .order(order)
                .sagaStatus(status)
                .build();
        saga.setId(1L);
        return saga;
    }

    private void stubRecoveryInfrastructure(OrderSagaState staleSaga, OrderSagaState confirmedSaga) {
        when(sagaRecoveryProperties.getStaleAfter()).thenReturn(Duration.ofMinutes(5));
        when(orderSagaStateRepository.findStaleSagas(any(List.class), any()))
                .thenAnswer(invocation -> {
                    List<SagaStatus> statuses = invocation.getArgument(0);
                    if (statuses.equals(List.of(SagaStatus.CONFIRMED))) {
                        return confirmedSaga == null ? List.of() : List.of(confirmedSaga);
                    }
                    return staleSaga == null ? List.of() : List.of(staleSaga);
                });
        when(orderSagaStateRepository.findByOrderId(ORDER_ID))
                .thenAnswer(_ -> Optional.of(saga(SagaStatus.STARTED, orderWithStatus(
                        Order.OrderStatus.PROCESSING))));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation ->
                invocation.getArgument(0));
        when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null));
    }

    private void stubBilling(boolean withdrawn) {
        when(billingServiceClient.getWithdrawStatus(ORDER_ID))
                .thenReturn(new WithdrawStatusDto(ORDER_ID, withdrawn, withdrawn ? 500L : null,
                        withdrawn ? PRICE : null));
    }

    private void stubWarehouse(ReservationStatus status) {
        if (status == null) {
            when(warehouseServiceClient.getReservation(ORDER_ID)).thenThrow(
                    new WarehouseServiceException(SagaStep.WAREHOUSE_RESERVE, null, "Not found",
                            HttpClientErrorException.create(HttpStatusCode.valueOf(404), "Not Found",
                                    null, null, null)));
            return;
        }
        ProductReservationResponseDto reservation = ProductReservationResponseDto.builder()
                .orderId(ORDER_ID)
                .reservationStatus(status)
                .build();
        when(warehouseServiceClient.getReservation(ORDER_ID)).thenReturn(
                new ProductReservationListResponseDto(ORDER_ID, List.of(reservation)));
    }

    private void stubDelivery(String status) {
        if (status == null) {
            when(deliveryServiceClient.getReservation(ORDER_ID)).thenThrow(
                    new DeliveryServiceException(SagaStep.DELIVERY_RESERVE, null, "Not found",
                            HttpClientErrorException.create(HttpStatusCode.valueOf(404), "Not Found",
                                    null, null, null)));
            return;
        }
        when(deliveryServiceClient.getReservation(ORDER_ID)).thenReturn(
                DeliveryReservationResponse.builder().orderId(ORDER_ID).status(status).build());
    }

    @Test
    @DisplayName("крах после billing: склад/доставка отсутствуют - только refund, заказ FAILED, сага COMPENSATED")
    void shouldCompensateOnlyBillingWhenCrashedAfterBilling() {
        Order order = orderWithStatus(Order.OrderStatus.PROCESSING);
        OrderSagaState staleSaga = saga(SagaStatus.BILLING_RESERVED, order);
        stubRecoveryInfrastructure(staleSaga, null);
        stubBilling(true);
        stubWarehouse(null);
        stubDelivery(null);

        sagaRecoveryService.recoverStaleSagas();

        verify(billingServiceClient).refundFunds(USER_ID, PRICE, ORDER_ID);
        verify(warehouseServiceClient, never()).cancel(anyLong());
        verify(deliveryServiceClient, never()).cancel(anyLong());
        verify(warehouseServiceClient, never()).confirm(anyLong());
        verify(deliveryServiceClient, never()).confirm(anyLong());
        assertThat(order.getOrderStatus()).isEqualTo(Order.OrderStatus.FAILED);
        assertSagaSavedWithStatus(SagaStatus.COMPENSATED);
        verify(notificationEventPublisher).send(any(NotificationEvent.class));
    }

    @Test
    @DisplayName("крах после warehouse: cancel склада + refund биллинга в обратном порядке, доставка не вызывалась")
    void shouldCompensateWarehouseAndBillingWhenCrashedAfterWarehouse() {
        Order order = orderWithStatus(Order.OrderStatus.PROCESSING);
        OrderSagaState staleSaga = saga(SagaStatus.WAREHOUSE_RESERVED, order);
        stubRecoveryInfrastructure(staleSaga, null);
        stubBilling(true);
        stubWarehouse(ReservationStatus.RESERVED);
        stubDelivery(null);

        sagaRecoveryService.recoverStaleSagas();

        verify(warehouseServiceClient).cancel(ORDER_ID);
        verify(billingServiceClient).refundFunds(USER_ID, PRICE, ORDER_ID);
        verify(deliveryServiceClient, never()).cancel(anyLong());
        assertThat(order.getOrderStatus()).isEqualTo(Order.OrderStatus.FAILED);
        assertSagaSavedWithStatus(SagaStatus.COMPENSATED);
    }

    @Test
    @DisplayName("все forward-шаги фактически выполнены - confirm-фаза, заказ PLACED, сага CONFIRMED")
    void shouldCompleteConfirmPhaseWhenAllForwardStepsDone() {
        Order order = orderWithStatus(Order.OrderStatus.PROCESSING);
        OrderSagaState staleSaga = saga(SagaStatus.DELIVERY_RESERVED, order);
        stubRecoveryInfrastructure(staleSaga, null);
        stubBilling(true);
        stubWarehouse(ReservationStatus.RESERVED);
        stubDelivery("RESERVED");

        sagaRecoveryService.recoverStaleSagas();

        verify(warehouseServiceClient).confirm(ORDER_ID);
        verify(deliveryServiceClient).confirm(ORDER_ID);
        verify(billingServiceClient, never()).refundFunds(anyLong(), any(), anyLong());
        verify(warehouseServiceClient, never()).cancel(anyLong());
        verify(deliveryServiceClient, never()).cancel(anyLong());
        assertThat(order.getOrderStatus()).isEqualTo(Order.OrderStatus.PLACED);
        assertSagaSavedWithStatus(SagaStatus.CONFIRMED);
        verify(notificationEventPublisher).send(any(NotificationEvent.class));
    }

    @Test
    @DisplayName("крах в COMPENSATING - компенсации продолжаются по фактическому состоянию")
    void shouldContinueCompensationWhenCrashedInCompensating() {
        Order order = orderWithStatus(Order.OrderStatus.PROCESSING);
        OrderSagaState staleSaga = saga(SagaStatus.COMPENSATING, order);
        stubRecoveryInfrastructure(staleSaga, null);
        stubBilling(true);
        stubWarehouse(ReservationStatus.RELEASED);
        stubDelivery("RESERVED");

        sagaRecoveryService.recoverStaleSagas();

        verify(deliveryServiceClient).cancel(ORDER_ID);
        verify(warehouseServiceClient, never()).cancel(anyLong());
        verify(billingServiceClient).refundFunds(USER_ID, PRICE, ORDER_ID);
        assertThat(order.getOrderStatus()).isEqualTo(Order.OrderStatus.FAILED);
        assertSagaSavedWithStatus(SagaStatus.COMPENSATED);
    }

    @Test
    @DisplayName("сага CONFIRMED без PLACED (крах перед финальным save) - заказ достраивается до PLACED")
    void shouldCompletePlacedOrderWhenSagaConfirmedButOrderNotPlaced() {
        Order order = orderWithStatus(Order.OrderStatus.PROCESSING);
        OrderSagaState confirmedSaga = saga(SagaStatus.CONFIRMED, order);
        stubRecoveryInfrastructure(null, confirmedSaga);

        sagaRecoveryService.recoverStaleSagas();

        assertThat(order.getOrderStatus()).isEqualTo(Order.OrderStatus.PLACED);
        verify(notificationEventPublisher).send(any(NotificationEvent.class));
        verify(billingServiceClient, never()).refundFunds(anyLong(), any(), anyLong());
    }

    @Test
    @DisplayName("сага CONFIRMED и заказ уже PLACED - повторная запись не выполняется")
    void shouldDoNothingWhenSagaConfirmedAndOrderAlreadyPlaced() {
        Order order = orderWithStatus(Order.OrderStatus.PLACED);
        OrderSagaState confirmedSaga = saga(SagaStatus.CONFIRMED, order);
        stubRecoveryInfrastructure(null, confirmedSaga);

        sagaRecoveryService.recoverStaleSagas();

        verify(orderRepository, never()).save(any());
        verify(notificationEventPublisher, never()).send(any());
    }

    @Test
    @DisplayName("компенсация упала (billing недоступен) - сага COMPENSATION_FAILED, заказ FAILED")
    void shouldMarkCompensationFailedWhenCompensationFails() {
        Order order = orderWithStatus(Order.OrderStatus.PROCESSING);
        OrderSagaState staleSaga = saga(SagaStatus.BILLING_RESERVED, order);
        stubRecoveryInfrastructure(staleSaga, null);
        stubBilling(true);
        stubWarehouse(null);
        stubDelivery(null);
        org.mockito.Mockito.doThrow(new BillingServiceException("Billing down"))
                .when(billingServiceClient).refundFunds(USER_ID, PRICE, ORDER_ID);

        sagaRecoveryService.recoverStaleSagas();

        assertThat(order.getOrderStatus()).isEqualTo(Order.OrderStatus.FAILED);
        assertSagaSavedWithStatus(SagaStatus.COMPENSATION_FAILED);
    }

    @Test
    @DisplayName("downstream недоступен при зондировании - recovery пропускает сагу до следующего запуска")
    void shouldSkipSagaWhenProbeFails() {
        Order order = orderWithStatus(Order.OrderStatus.PROCESSING);
        OrderSagaState staleSaga = saga(SagaStatus.BILLING_RESERVED, order);
        stubRecoveryInfrastructure(staleSaga, null);
        when(billingServiceClient.getWithdrawStatus(ORDER_ID))
                .thenThrow(new BillingServiceException("Billing unavailable"));

        sagaRecoveryService.recoverStaleSagas();

        verify(billingServiceClient, never()).refundFunds(anyLong(), any(), anyLong());
        verify(orderRepository, never()).save(any());
        assertThat(order.getOrderStatus()).isEqualTo(Order.OrderStatus.PROCESSING);
    }

    private void assertSagaSavedWithStatus(SagaStatus expected) {
        ArgumentCaptor<OrderSagaState> captor = ArgumentCaptor.forClass(OrderSagaState.class);
        verify(orderSagaStateRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(OrderSagaState::getSagaStatus)
                .contains(expected);
    }
}
