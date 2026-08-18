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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import ru.otus.hw.client.BillingServiceClient;
import ru.otus.hw.client.DeliveryServiceClient;
import ru.otus.hw.client.WarehouseServiceClient;
import ru.otus.hw.dto.OrderCreateDto;
import ru.otus.hw.dto.OrderCreateResult;
import ru.otus.hw.dto.OrderResponseDto;
import ru.otus.hw.dto.mapper.OrderMapper;
import ru.otus.hw.exception.BillingServiceException;
import ru.otus.hw.exception.ErrorCodes;
import ru.otus.hw.exception.IdempotencyConflictException;
import ru.otus.hw.models.IdempotencyKey;
import ru.otus.hw.models.Order;
import ru.otus.hw.models.OrderSagaState;
import ru.otus.hw.models.OrderSagaState.SagaStatus;
import ru.otus.hw.producer.NotificationEventPublisher;
import ru.otus.hw.repository.OrderRepository;
import ru.otus.hw.repository.OrderSagaStateRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Идемпотентность createOrder по заголовку Idempotency-Key
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderIdempotencyTest {

    private static final UUID IDEMPOTENCY_KEY =
            UUID.fromString("3f2b8c1a-9d4e-4a7f-8b2c-6e1d0a9b5c3d");

    private static final String REQUEST_HASH = "a".repeat(64);

    private static final String OTHER_REQUEST_HASH = "b".repeat(64);

    private static final Long ORDER_ID = 100L;

    private static final Long USER_ID = 7L;

    private static final BigDecimal PRICE = new BigDecimal("250.00");

    private static final Long PRODUCT_ID = 11L;

    private static final Integer QUANTITY = 3;

    private static final LocalDate DELIVERY_DATE = LocalDate.now().plusDays(1);

    private static final LocalTime SLOT_START = LocalTime.of(10, 0);

    private static final LocalTime SLOT_END = LocalTime.of(12, 0);

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
    private IdempotencyService idempotencyService;

    @Mock
    private OrderCreationService orderCreationService;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private NotificationEventPublisher notificationEventPublisher;

    @InjectMocks
    private OrderServiceImpl orderService;

    private OrderCreateDto createDto() {
        return new OrderCreateDto(USER_ID, PRICE, "test order", PRODUCT_ID, QUANTITY,
                DELIVERY_DATE, SLOT_START, SLOT_END);
    }

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

    private void stubSuccessfulSaga() {
        when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            if (order.getId() == null) {
                order.setId(ORDER_ID);
            }
            return order;
        });
        when(orderSagaStateRepository.findByOrderId(ORDER_ID)).thenAnswer(_ ->
                Optional.of(OrderSagaState.builder()
                        .order(orderWithStatus(Order.OrderStatus.PROCESSING))
                        .sagaStatus(SagaStatus.STARTED)
                        .build()));
        when(mapper.toOrderResponseDto(any(Order.class))).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            return new OrderResponseDto(order.getId(), order.getUserId(), order.getPrice(),
                    order.getDescription(), order.getProductId(), order.getQuantity(),
                    order.getDeliveryDate(), order.getSlotStart(), order.getSlotEnd(),
                    order.getOrderStatus());
        });
    }

    @Test
    @DisplayName("повтор ключа с тем же payload и сохранённым ответом -> тот же заказ (201-replay), заказ не создаётся")
    void shouldReplayStoredOrderWhenSameKeyAndSamePayload() {
        IdempotencyKey entry = IdempotencyKey.builder()
                .idempotencyKey(IDEMPOTENCY_KEY.toString())
                .userId(USER_ID)
                .requestHash(REQUEST_HASH)
                .orderId(ORDER_ID)
                .responseStatus(201)
                .responseBody("{}")
                .sagaStatus(SagaStatus.CONFIRMED)
                .build();
        OrderResponseDto storedResponse = new OrderResponseDto(ORDER_ID, USER_ID, PRICE, "test order",
                PRODUCT_ID, QUANTITY, DELIVERY_DATE, SLOT_START, SLOT_END, Order.OrderStatus.PLACED);
        when(idempotencyService.find(IDEMPOTENCY_KEY)).thenReturn(Optional.of(entry));
        when(idempotencyService.requestHash(createDto())).thenReturn(REQUEST_HASH);
        when(idempotencyService.readStoredResponse(entry)).thenReturn(storedResponse);

        OrderCreateResult result = orderService.createOrder(createDto(), IDEMPOTENCY_KEY);

        assertThat(result.kind()).isEqualTo(OrderCreateResult.Kind.REPLAYED_COMPLETED);
        assertThat(result.order()).isEqualTo(storedResponse);
        assertThat(result.order().orderStatus()).isEqualTo(Order.OrderStatus.PLACED);
        verify(orderRepository, never()).save(any());
        verify(orderCreationService, never()).createOrderWithKey(any(), any(), any());
        verify(billingServiceClient, never()).withdrawFunds(anyLong(), any(), anyLong());
    }

    @Test
    @DisplayName("повтор ключа с другим payload -> 409 IDEMPOTENCY_KEY_CONFLICT")
    void shouldThrowConflictWhenSameKeyButDifferentPayload() {
        IdempotencyKey entry = IdempotencyKey.builder()
                .idempotencyKey(IDEMPOTENCY_KEY.toString())
                .userId(USER_ID)
                .requestHash(REQUEST_HASH)
                .orderId(ORDER_ID)
                .sagaStatus(SagaStatus.CONFIRMED)
                .build();
        when(idempotencyService.find(IDEMPOTENCY_KEY)).thenReturn(Optional.of(entry));
        when(idempotencyService.requestHash(createDto())).thenReturn(OTHER_REQUEST_HASH);

        IdempotencyConflictException ex = assertThrows(IdempotencyConflictException.class,
                () -> orderService.createOrder(createDto(), IDEMPOTENCY_KEY));

        assertThat(ex.getCode()).isEqualTo(ErrorCodes.IDEMPOTENCY_KEY_CONFLICT);
        verify(orderRepository, never()).save(any());
        verify(billingServiceClient, never()).withdrawFunds(anyLong(), any(), anyLong());
    }

    @Test
    @DisplayName("повтор ключа с другим userId -> 409 IDEMPOTENCY_KEY_CONFLICT")
    void shouldThrowConflictWhenSameKeyButDifferentUser() {
        IdempotencyKey entry = IdempotencyKey.builder()
                .idempotencyKey(IDEMPOTENCY_KEY.toString())
                .userId(999L)
                .requestHash(REQUEST_HASH)
                .orderId(ORDER_ID)
                .sagaStatus(SagaStatus.CONFIRMED)
                .build();
        when(idempotencyService.find(IDEMPOTENCY_KEY)).thenReturn(Optional.of(entry));
        when(idempotencyService.requestHash(createDto())).thenReturn(REQUEST_HASH);

        assertThrows(IdempotencyConflictException.class,
                () -> orderService.createOrder(createDto(), IDEMPOTENCY_KEY));

        verify(orderRepository, never()).save(any());
    }

    @Test
    @DisplayName("повтор ключа после провала саги -> 200 с текущим FAILED-заказом, второй заказ не создаётся")
    void shouldReturnCurrentFailedOrderWhenSagaAlreadyFailed() {
        IdempotencyKey entry = IdempotencyKey.builder()
                .idempotencyKey(IDEMPOTENCY_KEY.toString())
                .userId(USER_ID)
                .requestHash(REQUEST_HASH)
                .orderId(ORDER_ID)
                .sagaStatus(SagaStatus.COMPENSATED)
                .build();
        Order failedOrder = orderWithStatus(Order.OrderStatus.FAILED);
        when(idempotencyService.find(IDEMPOTENCY_KEY)).thenReturn(Optional.of(entry));
        when(idempotencyService.requestHash(createDto())).thenReturn(REQUEST_HASH);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(failedOrder));
        when(mapper.toOrderResponseDto(failedOrder)).thenReturn(new OrderResponseDto(ORDER_ID, USER_ID, PRICE,
                "test order", PRODUCT_ID, QUANTITY, DELIVERY_DATE, SLOT_START, SLOT_END,
                Order.OrderStatus.FAILED));

        OrderCreateResult result = orderService.createOrder(createDto(), IDEMPOTENCY_KEY);

        assertThat(result.kind()).isEqualTo(OrderCreateResult.Kind.REPLAYED_CURRENT);
        assertThat(result.order().orderStatus()).isEqualTo(Order.OrderStatus.FAILED);
        verify(orderRepository, never()).save(any());
        verify(billingServiceClient, never()).withdrawFunds(anyLong(), any(), anyLong());
    }

    @Test
    @DisplayName("повтор ключа, пока сага выполняется -> 200 с текущим PROCESSING-заказом")
    void shouldReturnCurrentOrderWhenSagaInProgress() {
        IdempotencyKey entry = IdempotencyKey.builder()
                .idempotencyKey(IDEMPOTENCY_KEY.toString())
                .userId(USER_ID)
                .requestHash(REQUEST_HASH)
                .orderId(ORDER_ID)
                .sagaStatus(SagaStatus.BILLING_RESERVED)
                .build();
        Order processingOrder = orderWithStatus(Order.OrderStatus.PROCESSING);
        when(idempotencyService.find(IDEMPOTENCY_KEY)).thenReturn(Optional.of(entry));
        when(idempotencyService.requestHash(createDto())).thenReturn(REQUEST_HASH);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(processingOrder));
        when(mapper.toOrderResponseDto(processingOrder)).thenReturn(new OrderResponseDto(ORDER_ID, USER_ID, PRICE,
                "test order", PRODUCT_ID, QUANTITY, DELIVERY_DATE, SLOT_START, SLOT_END,
                Order.OrderStatus.PROCESSING));

        OrderCreateResult result = orderService.createOrder(createDto(), IDEMPOTENCY_KEY);

        assertThat(result.kind()).isEqualTo(OrderCreateResult.Kind.REPLAYED_CURRENT);
        assertThat(result.order().orderStatus()).isEqualTo(Order.OrderStatus.PROCESSING);
        verify(orderRepository, never()).save(any());
    }

    @Test
    @DisplayName("новый ключ - заказ создаётся, успешный ответ сохраняется для replay")
    void shouldCreateOrderAndStoreResponseWhenKeyIsNew() {
        stubSuccessfulSaga();
        when(idempotencyService.find(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
        when(idempotencyService.requestHash(createDto())).thenReturn(REQUEST_HASH);
        when(orderCreationService.createOrderWithKey(createDto(), IDEMPOTENCY_KEY, REQUEST_HASH))
                .thenReturn(orderWithStatus(Order.OrderStatus.PENDING));

        OrderCreateResult result = orderService.createOrder(createDto(), IDEMPOTENCY_KEY);

        assertThat(result.kind()).isEqualTo(OrderCreateResult.Kind.CREATED);
        assertThat(result.order().orderStatus()).isEqualTo(Order.OrderStatus.PLACED);
        verify(billingServiceClient).withdrawFunds(USER_ID, PRICE, ORDER_ID);
        verify(idempotencyService).recordSuccess(eq(IDEMPOTENCY_KEY), any(OrderResponseDto.class));
        verify(idempotencyService, never()).recordSagaStatus(any(), any());
    }

    @Test
    @DisplayName("новый ключ, сага упала - ключ остаётся с saga_status, ответ не сохраняется")
    void shouldRecordSagaStatusWhenSagaFailsWithKey() {
        when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            if (order.getId() == null) {
                order.setId(ORDER_ID);
            }
            return order;
        });
        when(orderSagaStateRepository.findByOrderId(ORDER_ID)).thenAnswer(_ ->
                Optional.of(OrderSagaState.builder()
                        .order(orderWithStatus(Order.OrderStatus.PROCESSING))
                        .sagaStatus(SagaStatus.STARTED)
                        .build()));
        when(idempotencyService.find(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
        when(idempotencyService.requestHash(createDto())).thenReturn(REQUEST_HASH);
        when(orderCreationService.createOrderWithKey(createDto(), IDEMPOTENCY_KEY, REQUEST_HASH))
                .thenReturn(orderWithStatus(Order.OrderStatus.PENDING));
        org.mockito.Mockito.doThrow(new BillingServiceException("Insufficient funds"))
                .when(billingServiceClient).withdrawFunds(USER_ID, PRICE, ORDER_ID);

        assertThrows(BillingServiceException.class, () -> orderService.createOrder(createDto(), IDEMPOTENCY_KEY));

        verify(idempotencyService).recordSagaStatus(IDEMPOTENCY_KEY, SagaStatus.COMPENSATED);
        verify(idempotencyService, never()).recordSuccess(any(), any());
    }

    @Test
    @DisplayName("гонка при вставке ключа (DataIntegrityViolation) -> повторный lookup и replay вместо 500")
    void shouldReplayWhenKeyInsertRacesWithConcurrentRequest() {
        IdempotencyKey concurrentEntry = IdempotencyKey.builder()
                .idempotencyKey(IDEMPOTENCY_KEY.toString())
                .userId(USER_ID)
                .requestHash(REQUEST_HASH)
                .orderId(ORDER_ID)
                .responseStatus(201)
                .responseBody("{}")
                .sagaStatus(SagaStatus.CONFIRMED)
                .build();
        OrderResponseDto storedResponse = new OrderResponseDto(ORDER_ID, USER_ID, PRICE, "test order",
                PRODUCT_ID, QUANTITY, DELIVERY_DATE, SLOT_START, SLOT_END, Order.OrderStatus.PLACED);
        when(idempotencyService.find(IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(concurrentEntry));
        when(idempotencyService.requestHash(createDto())).thenReturn(REQUEST_HASH);
        when(orderCreationService.createOrderWithKey(createDto(), IDEMPOTENCY_KEY, REQUEST_HASH))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));
        when(idempotencyService.readStoredResponse(concurrentEntry)).thenReturn(storedResponse);

        OrderCreateResult result = orderService.createOrder(createDto(), IDEMPOTENCY_KEY);

        assertThat(result.kind()).isEqualTo(OrderCreateResult.Kind.REPLAYED_COMPLETED);
        assertThat(result.order()).isEqualTo(storedResponse);
        verify(billingServiceClient, never()).withdrawFunds(anyLong(), any(), anyLong());
    }

    @Test
    @DisplayName("без ключа - обычный create, идемпотентность не задействуется")
    void shouldCreateOrderWithoutIdempotencyWhenKeyIsNull() {
        stubSuccessfulSaga();
        when(mapper.toEntity(any(OrderCreateDto.class))).thenReturn(orderWithStatus(Order.OrderStatus.PENDING));

        OrderResponseDto response = orderService.createOrder(createDto());

        assertThat(response.orderStatus()).isEqualTo(Order.OrderStatus.PLACED);
        verify(idempotencyService, never()).find(any());
        verify(idempotencyService, never()).recordSuccess(any(), any());
        verify(orderCreationService, never()).createOrderWithKey(any(), any(), any());
        verify(billingServiceClient).withdrawFunds(USER_ID, PRICE, ORDER_ID);
    }
}
