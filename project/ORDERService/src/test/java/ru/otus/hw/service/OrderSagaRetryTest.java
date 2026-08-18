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
import ru.otus.hw.client.BillingServiceClient;
import ru.otus.hw.client.DeliveryServiceClient;
import ru.otus.hw.client.WarehouseServiceClient;
import ru.otus.hw.dto.DeliveryReservationResponse;
import ru.otus.hw.dto.OrderCreateDto;
import ru.otus.hw.dto.OrderResponseDto;
import ru.otus.hw.dto.ProductReservationListResponseDto;
import ru.otus.hw.dto.ProductReservationResponseDto;
import ru.otus.hw.dto.ReservationStatus;
import ru.otus.hw.dto.mapper.OrderMapper;
import ru.otus.hw.exception.BillingServiceException;
import ru.otus.hw.exception.ErrorCodes;
import ru.otus.hw.models.Order;
import ru.otus.hw.models.OrderSagaState;
import ru.otus.hw.models.OrderSagaState.SagaStatus;
import ru.otus.hw.producer.NotificationEventPublisher;
import ru.otus.hw.repository.OrderRepository;
import ru.otus.hw.repository.OrderSagaStateRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Ретраи шагов саги на транзитные ошибки и разрешение неопределённости confirm
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderSagaRetryTest {

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

    private void stubSuccessfulSagaPersistence() {
        when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null));
        when(mapper.toEntity(any(OrderCreateDto.class))).thenReturn(orderWithStatus(Order.OrderStatus.PENDING));
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
    @DisplayName("5xx/таймаут на withdraw -> ретрай, успех со 2-й попытки, заказ PLACED")
    void shouldRetryTransientBillingErrorAndSucceed() {
        stubSuccessfulSagaPersistence();
        doThrow(new BillingServiceException("Billing timeout", null, true, null, null))
                .doNothing()
                .when(billingServiceClient).withdrawFunds(USER_ID, PRICE, ORDER_ID);

        OrderResponseDto response = orderService.createOrder(createDto());

        assertThat(response.orderStatus()).isEqualTo(Order.OrderStatus.PLACED);
        verify(billingServiceClient, times(2)).withdrawFunds(USER_ID, PRICE, ORDER_ID);
        verify(billingServiceClient, never()).refundFunds(anyLong(), any(), anyLong());
    }

    @Test
    @DisplayName("бизнес-отказ BILLING_INSUFFICIENT_FUNDS -> 0 ретраев, сразу компенсация")
    void shouldNotRetryBusinessFailure() {
        stubSuccessfulSagaPersistence();
        doThrow(new BillingServiceException("Insufficient funds", null, false,
                ErrorCodes.BILLING_INSUFFICIENT_FUNDS, 409))
                .when(billingServiceClient).withdrawFunds(USER_ID, PRICE, ORDER_ID);

        assertThrows(BillingServiceException.class, () -> orderService.createOrder(createDto()));

        verify(billingServiceClient, times(1)).withdrawFunds(USER_ID, PRICE, ORDER_ID);
        verify(warehouseServiceClient, never()).reserve(anyLong(), anyLong(), any(), anyString());
    }

    @Test
    @DisplayName("таймаут warehouse confirm, бронь уже CONFIRMED -> заказ PLACED без компенсации")
    void shouldProceedWhenConfirmFailsButReservationConfirmed() {
        stubSuccessfulSagaPersistence();
        doThrow(new BillingServiceException("confirm timeout", null, true, null, null))
                .when(warehouseServiceClient).confirm(ORDER_ID);
        ProductReservationResponseDto reservation = ProductReservationResponseDto.builder()
                .orderId(ORDER_ID)
                .reservationStatus(ReservationStatus.CONFIRMED)
                .build();
        when(warehouseServiceClient.getReservation(ORDER_ID))
                .thenReturn(new ProductReservationListResponseDto(ORDER_ID, List.of(reservation)));

        OrderResponseDto response = orderService.createOrder(createDto());

        assertThat(response.orderStatus()).isEqualTo(Order.OrderStatus.PLACED);
        verify(warehouseServiceClient, times(3)).confirm(ORDER_ID);
        verify(deliveryServiceClient).confirm(ORDER_ID);
        verify(billingServiceClient, never()).refundFunds(anyLong(), any(), anyLong());
        verify(warehouseServiceClient, never()).cancel(anyLong());
        verify(deliveryServiceClient, never()).cancel(anyLong());
    }

    @Test
    @DisplayName("таймаут delivery confirm, бронь RESERVED -> компенсация без подтверждения")
    void shouldCompensateWhenConfirmFailsAndReservationNotConfirmed() {
        stubSuccessfulSagaPersistence();
        doThrow(new BillingServiceException("confirm timeout", null, true, null, null))
                .when(deliveryServiceClient).confirm(ORDER_ID);
        when(deliveryServiceClient.getReservation(ORDER_ID))
                .thenReturn(DeliveryReservationResponse.builder().orderId(ORDER_ID).status("RESERVED").build());

        assertThrows(BillingServiceException.class, () -> orderService.createOrder(createDto()));

        verify(deliveryServiceClient).cancel(ORDER_ID);
        verify(warehouseServiceClient).cancel(ORDER_ID);
        verify(billingServiceClient).refundFunds(USER_ID, PRICE, ORDER_ID);
    }

    @Test
    @DisplayName("ретрай на 5xx склада -> успех со 2-й попытки")
    void shouldRetryWarehouseServerErrorAndSucceed() {
        stubSuccessfulSagaPersistence();
        doThrow(new ru.otus.hw.exception.WarehouseServiceException(
                OrderSagaState.SagaStep.WAREHOUSE_RESERVE, null, "Warehouse 500",
                org.springframework.web.client.HttpServerErrorException.create(
                        org.springframework.http.HttpStatusCode.valueOf(500), "Server Error",
                        null, null, null)))
                .doNothing()
                .when(warehouseServiceClient).reserve(anyLong(), anyLong(), any(), anyString());

        OrderResponseDto response = orderService.createOrder(createDto());

        assertThat(response.orderStatus()).isEqualTo(Order.OrderStatus.PLACED);
        verify(warehouseServiceClient, times(2)).reserve(anyLong(), anyLong(), any(), anyString());
    }
}
