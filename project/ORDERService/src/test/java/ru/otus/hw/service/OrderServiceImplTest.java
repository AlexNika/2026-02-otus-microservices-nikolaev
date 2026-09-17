package ru.otus.hw.service;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.http.HttpStatusCode;
import ru.otus.hw.client.BillingServiceClient;
import ru.otus.hw.client.DeliveryServiceClient;
import ru.otus.hw.client.WarehouseServiceClient;
import ru.otus.hw.dto.OrderCreateDto;
import ru.otus.hw.dto.OrderResponseDto;
import ru.otus.hw.dto.mapper.OrderMapper;
import ru.otus.hw.exception.BillingServiceException;
import ru.otus.hw.exception.DeliveryServiceException;
import ru.otus.hw.exception.ErrorCodes;
import ru.otus.hw.exception.OrderStateConflictException;
import ru.otus.hw.exception.WarehouseServiceException;
import ru.otus.hw.models.Order;
import ru.otus.hw.models.OrderSagaState;
import ru.otus.hw.models.OrderSagaState.SagaStatus;
import ru.otus.hw.models.OrderSagaState.SagaStep;
import ru.otus.hw.producer.NotificationEventPublisher;
import ru.otus.hw.repository.OrderRepository;
import ru.otus.hw.repository.OrderSagaStateRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderServiceImplTest {

    private static final Long ORDER_ID = 100L;

    private static final Long USER_ID = 7L;

    private static final BigDecimal PRICE = new BigDecimal("250.00");

    private static final Long PRODUCT_ID = 11L;

    private static final Integer QUANTITY = 3;

    private static final LocalDate DELIVERY_DATE = LocalDate.now().plusDays(1);

    private static final LocalTime SLOT_START = LocalTime.of(10, 0);

    private static final LocalTime SLOT_END = LocalTime.of(12, 0);

    private static final String EXPECTED_WAREHOUSE_IDEMPOTENCY_KEY =
            "order-" + ORDER_ID + "-p" + PRODUCT_ID;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderSagaStateRepository orderSagaStateRepository;

    @Mock
    private OrderMapper mapper;

    @Mock
    private BillingServiceClient billingServiceClient;

    @Mock
    private WarehouseServiceClient warehouseServiceClient;

    @Mock
    private DeliveryServiceClient deliveryServiceClient;

    @Mock
    private NotificationEventPublisher notificationEventPublisher;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private ru.otus.hw.metrics.SagaMetrics sagaMetrics;

    @Mock
    private ru.otus.hw.metrics.OrderBusinessMetrics orderBusinessMetrics;

    @InjectMocks
    private OrderServiceImpl orderService;

    private final List<SagaStatus> sagaTransitions = new ArrayList<>();

    private final List<Order.OrderStatus> orderStatusesOnSave = new ArrayList<>();

    private OrderSagaState currentSaga;

    private @NonNull Order orderWithStatus(Order.OrderStatus status) {
        Order order = Order.builder()
                .userId(USER_ID)
                .price(PRICE)
                .productId(PRODUCT_ID)
                .quantity(QUANTITY)
                .deliveryDate(DELIVERY_DATE)
                .slotStart(SLOT_START)
                .slotEnd(SLOT_END)
                .orderStatus(status)
                .build();
        order.setId(ORDER_ID);
        return order;
    }

    private OrderCreateDto createDto() {
        return new OrderCreateDto(PRICE, "test order", PRODUCT_ID, QUANTITY,
                DELIVERY_DATE, SLOT_START, SLOT_END);
    }

    private void stubCreateOrderPersistence() {
        when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null));
        Order created = orderWithStatus(Order.OrderStatus.PENDING);
        when(mapper.toEntity(any(OrderCreateDto.class))).thenReturn(created);
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            if (order.getId() == null) {
                order.setId(ORDER_ID);
            }
            orderStatusesOnSave.add(order.getOrderStatus());
            return order;
        });
        when(orderSagaStateRepository.save(any(OrderSagaState.class))).thenAnswer(invocation -> {
            OrderSagaState saga = invocation.getArgument(0);
            if (saga.getId() == null) {
                saga.setId(1L);
                currentSaga = saga;
            }
            sagaTransitions.add(saga.getSagaStatus());
            return saga;
        });
        when(orderSagaStateRepository.findByOrderId(ORDER_ID))
                .thenAnswer(invocation -> Optional.ofNullable(currentSaga));
        when(mapper.toOrderResponseDto(any(Order.class))).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            return new OrderResponseDto(order.getId(), order.getUserId(), order.getPrice(),
                    order.getDescription(), order.getProductId(), order.getQuantity(),
                    order.getDeliveryDate(), order.getSlotStart(), order.getSlotEnd(),
                    order.getOrderStatus());
        });
    }

    @Test
    @DisplayName("createOrder: успешная сага - все 3 шага + confirm, заказ PLACED, сага CONFIRMED, нотификация")
    void shouldCreateOrderWhenAllSagaStepsSucceed() {
        stubCreateOrderPersistence();

        OrderResponseDto response = orderService.createOrder(createDto(), USER_ID);

        assertThat(response.orderStatus()).isEqualTo(Order.OrderStatus.PLACED);
        verify(billingServiceClient).withdrawFunds(USER_ID, PRICE, ORDER_ID);
        verify(warehouseServiceClient).reserve(ORDER_ID, PRODUCT_ID, QUANTITY, EXPECTED_WAREHOUSE_IDEMPOTENCY_KEY);
        verify(deliveryServiceClient).reserve(ORDER_ID, USER_ID, DELIVERY_DATE, SLOT_START, SLOT_END);
        verify(warehouseServiceClient).confirm(ORDER_ID);
        verify(deliveryServiceClient).confirm(ORDER_ID);
        assertThat(sagaTransitions).containsExactly(
                SagaStatus.STARTED,
                SagaStatus.BILLING_RESERVED,
                SagaStatus.WAREHOUSE_RESERVED,
                SagaStatus.DELIVERY_RESERVED,
                SagaStatus.CONFIRMED);
        assertThat(orderStatusesOnSave).containsExactly(
                Order.OrderStatus.PENDING, Order.OrderStatus.PROCESSING, Order.OrderStatus.PLACED);
        verify(notificationEventPublisher).send(argThat(event ->
                event.status().equals(Order.OrderStatus.PLACED.name())
                        && event.message().contains("placed successfully")
                        && event.eventId() != null));
    }

    @Test
    @DisplayName("createOrder: отказ биллинга - компенсаций нет, заказ FAILED, сага COMPENSATED")
    void shouldFailOrderWhenBillingWithdrawFails() {
        stubCreateOrderPersistence();
        doThrow(new BillingServiceException("Insufficient funds"))
                .when(billingServiceClient).withdrawFunds(USER_ID, PRICE, ORDER_ID);

        BillingServiceException ex = assertThrows(BillingServiceException.class,
                () -> orderService.createOrder(createDto(), USER_ID));

        assertThat(ex.getMessage()).contains("Insufficient funds");
        verify(billingServiceClient, never()).refundFunds(anyLong(), any(), anyLong());
        verify(warehouseServiceClient, never()).reserve(anyLong(), anyLong(), any(Integer.class), anyString());
        verify(warehouseServiceClient, never()).cancel(anyLong());
        verify(deliveryServiceClient, never()).reserve(anyLong(), anyLong(), any(), any(), any());
        verify(deliveryServiceClient, never()).cancel(anyLong());
        verify(warehouseServiceClient, never()).confirm(anyLong());
        verify(deliveryServiceClient, never()).confirm(anyLong());
        assertThat(sagaTransitions).containsExactly(
                SagaStatus.STARTED, SagaStatus.COMPENSATING, SagaStatus.COMPENSATED);
        verifyFailedOrderSaved();
        verifyFailureNotification();
    }

    @Test
    @DisplayName("createOrder: отказ склада - выполнен ровно refund биллинга, cancel доставки не вызывался")
    void shouldFailOrderWhenWarehouseReserveFails() {
        stubCreateOrderPersistence();
        doThrow(new WarehouseServiceException(SagaStep.WAREHOUSE_RESERVE,
                ErrorCodes.INSUFFICIENT_STOCK, "Not enough stock"))
                .when(warehouseServiceClient).reserve(ORDER_ID, PRODUCT_ID, QUANTITY,
                        EXPECTED_WAREHOUSE_IDEMPOTENCY_KEY);

        WarehouseServiceException ex = assertThrows(WarehouseServiceException.class,
                () -> orderService.createOrder(createDto(), USER_ID));

        assertThat(ex.getCode()).isEqualTo(ErrorCodes.INSUFFICIENT_STOCK);
        verify(billingServiceClient).refundFunds(USER_ID, PRICE, ORDER_ID);
        verify(warehouseServiceClient, never()).cancel(anyLong());
        verify(deliveryServiceClient, never()).reserve(anyLong(), anyLong(), any(), any(), any());
        verify(deliveryServiceClient, never()).cancel(anyLong());
        verify(warehouseServiceClient, never()).confirm(anyLong());
        verify(deliveryServiceClient, never()).confirm(anyLong());
        assertThat(sagaTransitions).containsExactly(
                SagaStatus.STARTED, SagaStatus.BILLING_RESERVED,
                SagaStatus.COMPENSATING, SagaStatus.COMPENSATED);
        verifyFailedOrderSaved();
        verifyFailureNotification();
    }

    @Test
    @DisplayName("createOrder: отказ доставки - cancel склада + refund биллинга, confirm-фаза не запускалась")
    void shouldFailOrderWhenDeliveryReserveFails() {
        stubCreateOrderPersistence();
        doThrow(new DeliveryServiceException(SagaStep.DELIVERY_RESERVE,
                ErrorCodes.DELIVERY_NO_FREE_COURIER, "No free courier"))
                .when(deliveryServiceClient).reserve(ORDER_ID, USER_ID, DELIVERY_DATE, SLOT_START, SLOT_END);

        DeliveryServiceException ex = assertThrows(DeliveryServiceException.class,
                () -> orderService.createOrder(createDto(), USER_ID));

        assertThat(ex.getCode()).isEqualTo(ErrorCodes.DELIVERY_NO_FREE_COURIER);
        verify(deliveryServiceClient, never()).cancel(anyLong());
        verify(warehouseServiceClient).cancel(ORDER_ID);
        verify(billingServiceClient).refundFunds(USER_ID, PRICE, ORDER_ID);
        verify(warehouseServiceClient, never()).confirm(anyLong());
        verify(deliveryServiceClient, never()).confirm(anyLong());
        assertThat(sagaTransitions).containsExactly(
                SagaStatus.STARTED, SagaStatus.BILLING_RESERVED, SagaStatus.WAREHOUSE_RESERVED,
                SagaStatus.COMPENSATING, SagaStatus.COMPENSATED);
        verifyFailedOrderSaved();
        verifyFailureNotification();
    }

    @Test
    @DisplayName("createOrder: отказ компенсации - сага COMPENSATION_FAILED, заказ FAILED, шаг и причина сохранены")
    void shouldMarkSagaCompensationFailedWhenCompensationFails() {
        stubCreateOrderPersistence();
        doThrow(new DeliveryServiceException(SagaStep.DELIVERY_RESERVE,
                ErrorCodes.DELIVERY_NO_FREE_COURIER, "No free courier"))
                .when(deliveryServiceClient).reserve(ORDER_ID, USER_ID, DELIVERY_DATE, SLOT_START, SLOT_END);
        doThrow(new WarehouseServiceException(SagaStep.WAREHOUSE_CANCEL, null, "Warehouse down"))
                .when(warehouseServiceClient).cancel(ORDER_ID);

        assertThrows(DeliveryServiceException.class, () -> orderService.createOrder(createDto(), USER_ID));

        verify(deliveryServiceClient, never()).cancel(anyLong());
        verify(billingServiceClient).refundFunds(USER_ID, PRICE, ORDER_ID);
        assertThat(sagaTransitions).containsExactly(
                SagaStatus.STARTED, SagaStatus.BILLING_RESERVED, SagaStatus.WAREHOUSE_RESERVED,
                SagaStatus.COMPENSATING, SagaStatus.COMPENSATION_FAILED);
        verify(orderSagaStateRepository, org.mockito.Mockito.atLeastOnce()).save(argThat(saga ->
                saga.getSagaStatus() == SagaStatus.COMPENSATION_FAILED
                        && saga.getFailureStep() == SagaStep.DELIVERY_RESERVE
                        && saga.getFailureReason() != null));
        verifyFailedOrderSaved();
        verifyFailureNotification();
    }

    private void verifyFailedOrderSaved() {
        assertThat(orderStatusesOnSave).containsExactly(
                Order.OrderStatus.PENDING, Order.OrderStatus.PROCESSING, Order.OrderStatus.FAILED);
    }

    private void verifyFailureNotification() {
        verify(notificationEventPublisher).send(argThat(event ->
                event.status().equals(Order.OrderStatus.FAILED.name())
                        && event.message().contains("failed")
                        && event.eventId() != null));
    }

    @Test
    @DisplayName("cancelOrder: должен бросить ORDER_STATE_CONFLICT, если заказ не в статусе PLACED")
    void shouldThrowStateConflictWhenCancelNonPlacedOrder() {
        Order order = orderWithStatus(Order.OrderStatus.PENDING);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        OrderStateConflictException ex = assertThrows(OrderStateConflictException.class,
                () -> orderService.cancelOrder(ORDER_ID));

        assertThat(ex.getCode()).isEqualTo(ErrorCodes.ORDER_STATE_CONFLICT);
        assertThat(order.getOrderStatus()).isEqualTo(Order.OrderStatus.PENDING);
        verify(billingServiceClient, never()).refundFunds(anyLong(), any(), anyLong());
        verify(orderRepository, never()).save(any());
    }

    @Test
    @DisplayName("cancelOrder: PLACED с RESERVED-бронями - все 3 отката, заказ CANCELED")
    void shouldCancelPlacedOrderWithReservedResources() {
        Order order = orderWithStatus(Order.OrderStatus.PLACED);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation ->
                invocation.getArgument(0));
        when(mapper.toOrderResponseDto(any(Order.class))).thenAnswer(invocation -> {
            Order o = invocation.getArgument(0);
            return new OrderResponseDto(o.getId(), o.getUserId(), o.getPrice(), o.getDescription(),
                    o.getProductId(), o.getQuantity(), o.getDeliveryDate(), o.getSlotStart(),
                    o.getSlotEnd(), o.getOrderStatus());
        });
        when(deliveryServiceClient.cancel(ORDER_ID))
                .thenReturn(ru.otus.hw.dto.CancelDeliveryResponse.CancelDeliveryResult.CANCELLED);

        OrderResponseDto response = orderService.cancelOrder(ORDER_ID);

        assertThat(order.getOrderStatus()).isEqualTo(Order.OrderStatus.CANCELED);
        assertThat(response.orderStatus()).isEqualTo(Order.OrderStatus.CANCELED);
        verify(billingServiceClient).refundFunds(USER_ID, PRICE, ORDER_ID);
        verify(warehouseServiceClient).cancel(ORDER_ID);
        verify(deliveryServiceClient).cancel(ORDER_ID);
        verify(orderRepository).save(order);
    }

    @Test
    @DisplayName("cancelOrder: PLACED, бронь склада уже CONFIRMED (400) - конфликт, статус не меняется")
    void shouldThrowConflictWhenWarehouseReservationConfirmed() {
        Order order = orderWithStatus(Order.OrderStatus.PLACED);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        doThrow(new WarehouseServiceException(SagaStep.WAREHOUSE_CANCEL, null, "Cannot cancel CONFIRMED",
                HttpClientErrorException.create(HttpStatusCode.valueOf(400), "Bad Request",
                        null, null, null)))
                .when(warehouseServiceClient).cancel(ORDER_ID);

        OrderStateConflictException ex = assertThrows(OrderStateConflictException.class,
                () -> orderService.cancelOrder(ORDER_ID));

        assertThat(ex.getCode()).isEqualTo(ErrorCodes.ORDER_STATE_CONFLICT);
        assertThat(order.getOrderStatus()).isEqualTo(Order.OrderStatus.PLACED);
        verify(billingServiceClient).refundFunds(USER_ID, PRICE, ORDER_ID);
        verify(warehouseServiceClient).cancel(ORDER_ID);
        verify(deliveryServiceClient, never()).cancel(anyLong());
        verify(orderRepository, never()).save(any());
    }

    @Test
    @DisplayName("cancelOrder: PLACED, склад вернул машинный код RESERVATION_ALREADY_CONFIRMED - конфликт")
    void shouldThrowConflictOnWarehouseAlreadyConfirmedCode() {
        Order order = orderWithStatus(Order.OrderStatus.PLACED);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        doThrow(new WarehouseServiceException(SagaStep.WAREHOUSE_CANCEL,
                ErrorCodes.RESERVATION_ALREADY_CONFIRMED, "Cannot cancel reservation in status CONFIRMED"))
                .when(warehouseServiceClient).cancel(ORDER_ID);

        OrderStateConflictException ex = assertThrows(OrderStateConflictException.class,
                () -> orderService.cancelOrder(ORDER_ID));

        assertThat(ex.getCode()).isEqualTo(ErrorCodes.ORDER_STATE_CONFLICT);
        assertThat(order.getOrderStatus()).isEqualTo(Order.OrderStatus.PLACED);
        verify(deliveryServiceClient, never()).cancel(anyLong());
        verify(orderRepository, never()).save(any());
    }

    @Test
    @DisplayName("cancelOrder: PLACED, бронь доставки уже CONFIRMED (409) - конфликт, статус не меняется")
    void shouldThrowConflictWhenDeliveryReservationConfirmed() {
        Order order = orderWithStatus(Order.OrderStatus.PLACED);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        doThrow(new DeliveryServiceException(SagaStep.DELIVERY_CANCEL,
                ErrorCodes.DELIVERY_RESERVATION_STATE_CONFLICT, "Cannot cancel CONFIRMED",
                HttpClientErrorException.create(HttpStatusCode.valueOf(409), "Conflict",
                        null, null, null)))
                .when(deliveryServiceClient).cancel(ORDER_ID);

        OrderStateConflictException ex = assertThrows(OrderStateConflictException.class,
                () -> orderService.cancelOrder(ORDER_ID));

        assertThat(ex.getCode()).isEqualTo(ErrorCodes.ORDER_STATE_CONFLICT);
        assertThat(order.getOrderStatus()).isEqualTo(Order.OrderStatus.PLACED);
        verify(billingServiceClient).refundFunds(USER_ID, PRICE, ORDER_ID);
        verify(warehouseServiceClient).cancel(ORDER_ID);
        verify(deliveryServiceClient).cancel(ORDER_ID);
        verify(orderRepository, never()).save(any());
    }

    @Test
    @DisplayName("cancelOrder: PLACED, откат склада упал с 500 - пробрасываем ошибку, статус не меняется")
    void shouldRethrowWhenWarehouseCancelFailsWithServerError() {
        Order order = orderWithStatus(Order.OrderStatus.PLACED);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        WarehouseServiceException serverError = new WarehouseServiceException(
                SagaStep.WAREHOUSE_CANCEL, null, "Warehouse returned 500",
                org.springframework.web.client.HttpServerErrorException.create(
                        HttpStatusCode.valueOf(500), "Server Error",
                        null, null, null));
        doThrow(serverError).when(warehouseServiceClient).cancel(ORDER_ID);

        WarehouseServiceException ex = assertThrows(WarehouseServiceException.class,
                () -> orderService.cancelOrder(ORDER_ID));

        assertThat(ex).isSameAs(serverError);
        assertThat(order.getOrderStatus()).isEqualTo(Order.OrderStatus.PLACED);
        verify(orderRepository, never()).save(any());
    }
}
