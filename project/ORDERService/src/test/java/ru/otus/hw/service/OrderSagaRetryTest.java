package ru.otus.hw.service;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatusCode;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpServerErrorException;
import ru.otus.hw.client.BillingServiceClient;
import ru.otus.hw.client.DeliveryServiceClient;
import ru.otus.hw.client.WarehouseServiceClient;
import ru.otus.hw.config.ResilienceConfig;
import ru.otus.hw.dto.DeliveryReservationResponse;
import ru.otus.hw.dto.OrderCreateDto;
import ru.otus.hw.dto.OrderResponseDto;
import ru.otus.hw.dto.ProductReservationListResponseDto;
import ru.otus.hw.dto.ProductReservationResponseDto;
import ru.otus.hw.dto.ReservationStatus;
import ru.otus.hw.dto.mapper.OrderMapper;
import ru.otus.hw.exception.BillingServiceException;
import ru.otus.hw.exception.ErrorCodes;
import ru.otus.hw.exception.WarehouseServiceException;
import ru.otus.hw.models.Order;
import ru.otus.hw.models.OrderSagaState;
import ru.otus.hw.models.OrderSagaState.SagaStatus;
import ru.otus.hw.producer.NotificationEventPublisher;
import ru.otus.hw.repository.OrderRepository;
import ru.otus.hw.repository.OrderSagaStateRepository;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
 * Семантика отказоустойчивости шагов саги после переезда ретраев на
 * {@code @Retry}/{@code @CircuitBreaker}/{@code @RateLimiter} клиентов (Resilience4j):
 * <ul>
 *   <li>ретраи/классификация проверяются на программно собранных декораторах с теми же
 *       кастомайзерами, что и в проде ({@link ResilienceConfig});</li>
 *   <li>поведение {@code OrderServiceImpl} (confirm-пробы, компенсации) - на моках,
 *       где клиент вызывается один раз (ретраи внутри клиентского прокси).</li>
 * </ul>
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

    @Mock
    private ru.otus.hw.metrics.SagaMetrics sagaMetrics;

    @Mock
    private ru.otus.hw.metrics.OrderBusinessMetrics orderBusinessMetrics;

    @InjectMocks
    private OrderServiceImpl orderService;

    private OrderCreateDto createDto() {
        return new OrderCreateDto(PRICE, "test order", PRODUCT_ID, QUANTITY,
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

    /**
     * Аналог прод-конфига: 3 попытки, бэкофф 200мс × 2, классификация из
     * {@link ResilienceConfig}, {@code CallNotPermittedException} не ретраится.
     */
    private Retry retry(String instance) {
        RetryConfig.Builder<?> builder = RetryConfig.custom()
                .maxAttempts(3)
                .intervalFunction(IntervalFunction.ofExponentialBackoff(Duration.ofMillis(200), 2.0))
                .ignoreExceptions(CallNotPermittedException.class);
        new ResilienceConfig().billingRetryCustomizer().customize(builder);
        return Retry.of(instance, builder.build());
    }

    private CircuitBreaker circuitBreaker(String instance) {
        CircuitBreakerConfig.Builder builder = CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .permittedNumberOfCallsInHalfOpenState(3);
        new ResilienceConfig().billingCircuitBreakerCustomizer().customize(builder);
        return CircuitBreaker.of(instance, builder.build());
    }

    /** Retry( CircuitBreaker( вызов ) ) - как порядок аспектов в проде. */
    private Runnable decorated(Retry retry, CircuitBreaker circuitBreaker, Runnable step) {
        return Retry.decorateRunnable(retry,
                CircuitBreaker.decorateRunnable(circuitBreaker, step));
    }

    private BillingServiceException transientBillingError() {
        return new BillingServiceException("Billing timeout", null, true, null, null);
    }

    private BillingServiceException businessBillingError() {
        return new BillingServiceException("Insufficient funds", null, false,
                ErrorCodes.BILLING_INSUFFICIENT_FUNDS, 409);
    }

    private WarehouseServiceException warehouseServerError(OrderSagaState.SagaStep step) {
        return new WarehouseServiceException(step, null, "Warehouse 500",
                HttpServerErrorException.create(HttpStatusCode.valueOf(500), "Server Error",
                        null, null, null));
    }

    @Test
    @DisplayName("транзитный сбой биллинга -> ретрай, успех со 2-й попытки (ровно 2 вызова)")
    void shouldRetryTransientBillingErrorAndSucceed() {
        AtomicInteger attempts = new AtomicInteger();
        Runnable step = () -> {
            if (attempts.incrementAndGet() == 1) {
                throw transientBillingError();
            }
        };

        decorated(retry("billingService"), circuitBreaker("billingService"), step).run();

        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("бизнес-отказ (4хх) -> 0 ретраев, исключение пробрасывается с кодом")
    void shouldNotRetryBusinessFailure() {
        AtomicInteger attempts = new AtomicInteger();
        Runnable step = () -> {
            attempts.incrementAndGet();
            throw businessBillingError();
        };

        Runnable runnable = decorated(retry("billingService"), circuitBreaker("billingService"), step);

        assertThatThrownBy(runnable::run)
                .isInstanceOf(BillingServiceException.class)
                .extracting(ex -> ((BillingServiceException) ex).getCode())
                .isEqualTo(ErrorCodes.BILLING_INSUFFICIENT_FUNDS);
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("5xx склада -> ретрай, успех со 2-й попытки")
    void shouldRetryWarehouseServerErrorAndSucceed() {
        AtomicInteger attempts = new AtomicInteger();
        Runnable step = () -> {
            if (attempts.incrementAndGet() == 1) {
                throw warehouseServerError(OrderSagaState.SagaStep.WAREHOUSE_RESERVE);
            }
        };

        decorated(retry("warehouseService"), circuitBreaker("warehouseService"), step).run();

        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("исключение с кодом RATE_LIMITED (fallback лимитера) ретраится")
    void shouldRetryRateLimitedFailure() {
        AtomicInteger attempts = new AtomicInteger();
        Runnable step = () -> {
            if (attempts.incrementAndGet() == 1) {
                throw new WarehouseServiceException(OrderSagaState.SagaStep.WAREHOUSE_RESERVE,
                        ErrorCodes.RATE_LIMITED, "rate limit exhausted");
            }
        };

        decorated(retry("warehouseService"), circuitBreaker("warehouseService"), step).run();

        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("открытый circuit breaker -> CallNotPermitted без ретраев (1 попытка)")
    void shouldFailFastWhenCircuitBreakerOpen() {
        CircuitBreaker circuitBreaker = circuitBreaker("warehouseService");
        circuitBreaker.transitionToOpenState();
        AtomicInteger attempts = new AtomicInteger();

        Runnable runnable = decorated(retry("warehouseService"), circuitBreaker, attempts::incrementAndGet);

        assertThatThrownBy(runnable::run).isInstanceOf(CallNotPermittedException.class);
        assertThat(attempts.get()).isZero();
    }

    @Test
    @DisplayName("бизнес-отказы не открывают circuit breaker, транзитные сбои открывают")
    void shouldRecordOnlyTransientFailuresInCircuitBreaker() {
        CircuitBreaker circuitBreaker = circuitBreaker("billingService");
        Retry noWaitRetry = retryWithoutDelays("billingService");

        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(decorated(noWaitRetry, circuitBreaker,
                    () -> {
                        throw businessBillingError();
                    })::run).isInstanceOf(BillingServiceException.class);
        }
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(decorated(noWaitRetry, circuitBreaker,
                    () -> {
                        throw transientBillingError();
                    })::run).isInstanceOf(BillingServiceException.class);
        }
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    private Retry retryWithoutDelays(String instance) {
        RetryConfig.Builder<?> builder = RetryConfig.custom()
                .maxAttempts(1)
                .ignoreExceptions(CallNotPermittedException.class);
        new ResilienceConfig().billingRetryCustomizer().customize(builder);
        return Retry.of(instance, builder.build());
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

        OrderResponseDto response = orderService.createOrder(createDto(), USER_ID);

        assertThat(response.orderStatus()).isEqualTo(Order.OrderStatus.PLACED);
        verify(warehouseServiceClient, times(1)).confirm(ORDER_ID);
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

        assertThrows(BillingServiceException.class, () -> orderService.createOrder(createDto(), USER_ID));

        verify(deliveryServiceClient, times(1)).confirm(ORDER_ID);
        verify(deliveryServiceClient).cancel(ORDER_ID);
        verify(warehouseServiceClient).cancel(ORDER_ID);
        verify(billingServiceClient).refundFunds(USER_ID, PRICE, ORDER_ID);
    }
}
