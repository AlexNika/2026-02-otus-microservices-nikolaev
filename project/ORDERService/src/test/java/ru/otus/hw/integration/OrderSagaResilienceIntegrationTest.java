package ru.otus.hw.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import ru.otus.hw.client.WarehouseServiceClient;
import ru.otus.hw.dto.OrderCreateDto;
import ru.otus.hw.dto.OrderResponseDto;
import ru.otus.hw.exception.WarehouseServiceException;
import ru.otus.hw.models.Order;
import ru.otus.hw.models.OrderSagaState;
import ru.otus.hw.producer.NotificationEventPublisher;
import ru.otus.hw.repository.OrderRepository;
import ru.otus.hw.repository.OrderSagaStateRepository;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Интеграционные тесты отказоустойчивости саги: Retry + CircuitBreaker + RateLimiter
 * (Resilience4j) на клиентах, пороговые значения ускорены через {@code @DynamicPropertySource}
 * (малое окно, {@code waitDurationInOpenState=2s}, {@code failureRateThreshold=100}, чтобы две
 * неудачи + успех в тесте ретраев не открывали circuit раньше времени).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import(OrderSagaResilienceIntegrationTest.HttpClientTestConfig.class)
class OrderSagaResilienceIntegrationTest {
    @TestConfiguration
    static class HttpClientTestConfig {
        @Bean
        RestClientCustomizer http11RequestFactory(Environment environment) {
            Duration connectTimeout = environment.getProperty("spring.http.client.connect-timeout", Duration.class);
            Duration readTimeout = environment.getProperty("spring.http.client.read-timeout", Duration.class);

            HttpClient.Builder httpClientBuilder = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1);
            if (connectTimeout != null) {
                httpClientBuilder.connectTimeout(connectTimeout);
            }
            JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClientBuilder.build());
            if (readTimeout != null) {
                requestFactory.setReadTimeout(readTimeout);
            }
            return builder -> builder.requestFactory(requestFactory);
        }

        @Bean
        RestTemplateBuilder testRestTemplateBuilder() {
            return new RestTemplateBuilder();
        }
    }

    private static final AtomicLong USER_SEQUENCE = new AtomicLong(9500);

    private static final Long PRODUCT_ID = 11L;

    private static final Integer QUANTITY = 3;

    private static final BigDecimal PRICE = new BigDecimal("250.0000");

    private static final LocalDate DELIVERY_DATE = LocalDate.now().plusDays(1);

    private static final LocalTime SLOT_START = LocalTime.of(10, 0);

    private static final LocalTime SLOT_END = LocalTime.of(12, 0);

    private static final String WAREHOUSE_CB = "warehouseService";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.11");

    @RegisterExtension
    static WireMockExtension billingMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @RegisterExtension
    static WireMockExtension warehouseMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @RegisterExtension
    static WireMockExtension deliveryMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void configureProperties(@NonNull DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.billing-service-url", billingMock::baseUrl);
        registry.add("app.warehouse-service-url", warehouseMock::baseUrl);
        registry.add("app.delivery-service-url", deliveryMock::baseUrl);
        registry.add("app.internal-api-key", () -> "test-internal-api-key");
        registry.add("app.security.jwt-secret-key",
                () -> "integration-test-jwt-secret-key-long-enough-for-hmac-sha-2026");
        registry.add("app.idempotency.ttl", () -> "24h");
        registry.add("app.idempotency.cleanup-interval", () -> "1h");

        registry.add("resilience4j.circuitbreaker.instances.warehouseService.sliding-window-size", () -> "3");
        registry.add("resilience4j.circuitbreaker.instances.warehouseService.minimum-number-of-calls", () -> "3");
        registry.add("resilience4j.circuitbreaker.instances.warehouseService.failure-rate-threshold", () -> "100");
        registry.add("resilience4j.circuitbreaker.instances.warehouseService.wait-duration-in-open-state", () -> "2s");
        registry.add("resilience4j.circuitbreaker.instances.warehouseService.permitted-number-of-calls-in-half-open-state",
                () -> "1");
        registry.add("resilience4j.circuitbreaker.instances.warehouseService.automatic-transition-from-open-to-half-open-enabled",
                () -> "true");
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderSagaStateRepository orderSagaStateRepository;

    @Autowired
    private ru.otus.hw.security.JwtTokenProvider jwtTokenProvider;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private RateLimiterRegistry rateLimiterRegistry;

    @Autowired
    private WarehouseServiceClient warehouseServiceClient;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private NotificationEventPublisher notificationEventPublisher;

    @BeforeEach
    void resetResilienceState() {
        circuitBreakerRegistry.getAllCircuitBreakers()
                .forEach(circuitBreaker -> circuitBreaker.transitionToClosedState());
        RateLimiterConfig defaults = RateLimiterConfig.custom()
                .limitForPeriod(50)
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .timeoutDuration(Duration.ofSeconds(1))
                .build();
        rateLimiterRegistry.getAllRateLimiters().stream().toList()
                .forEach(rateLimiter -> rateLimiterRegistry.replace(rateLimiter.getName(),
                        RateLimiter.of(rateLimiter.getName(), defaults)));
    }

    private CircuitBreaker warehouseCircuitBreaker() {
        return circuitBreakerRegistry.circuitBreaker(WAREHOUSE_CB);
    }

    @Test
    @DisplayName("транзитный 500 -> ретраи, успех ровно с 3-й попытки, заказ PLACED")
    void shouldRetryTransientWarehouseErrorAndPlaceOrder() {
        Long userId = nextUserId();
        stubHappyPathExceptWarehouseReserve();
        stubWarehouseReserveSucceedsOnThirdAttempt();

        ResponseEntity<OrderResponseDto> response = createOrder(userId, OrderResponseDto.class, null);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().orderStatus()).isEqualTo(Order.OrderStatus.PLACED);

        warehouseMock.verify(3, postRequestedFor(urlEqualTo("/internal/products/reservations")));

        Long orderId = response.getBody().id();
        assertOrderStatus(orderId, Order.OrderStatus.PLACED);
        assertSagaStatus(orderId, OrderSagaState.SagaStatus.CONFIRMED);
        assertThat(warehouseCircuitBreaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("бизнес-4хх склада -> без ретраев (1 вызов), компенсация: refund выполнен")
    void shouldNotRetryBusinessFailureAndCompensate() {
        Long userId = nextUserId();
        stubBillingWithdrawOk();
        stubBillingRefundOk();
        stubWarehouseReserveError(409, "Not enough stock", "INSUFFICIENT_STOCK");

        ResponseEntity<String> response = createOrder(userId, String.class, null);

        assertThat(response.getStatusCode().value()).isEqualTo(409);

        warehouseMock.verify(1, postRequestedFor(urlEqualTo("/internal/products/reservations")));
        billingMock.verify(1, postRequestedFor(urlEqualTo("/internal/order/refund")));

        Long orderId = singleOrderIdByUser(userId);
        assertOrderStatus(orderId, Order.OrderStatus.FAILED);
        assertSaga(orderId, OrderSagaState.SagaStatus.COMPENSATED, OrderSagaState.SagaStep.WAREHOUSE_RESERVE);
    }

    @Test
    @DisplayName("серия сбоев -> circuit OPEN -> следующий заказ: быстрый 503 с кодом "
            + "CIRCUIT_BREAKER_OPEN, компенсация фактически зарезервированного")
    void shouldFastFailWhenCircuitBreakerOpen() {
        Long userId = nextUserId();
        stubBillingWithdrawOk();
        stubBillingRefundOk();
        stubWarehouseReserveError(500, "Internal error", null);

        ResponseEntity<String> first = createOrder(userId, String.class, null);
        assertThat(first.getStatusCode().value()).isEqualTo(409);
        assertThat(warehouseCircuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
        warehouseMock.verify(3, postRequestedFor(urlEqualTo("/internal/products/reservations")));

        warehouseCircuitBreaker().transitionToOpenState();

        long startedAtMs = System.currentTimeMillis();
        ResponseEntity<String> second = createOrder(nextUserId(), String.class, null);
        long elapsedMs = System.currentTimeMillis() - startedAtMs;

        assertThat(second.getStatusCode().value()).isEqualTo(503);
        assertThat(second.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("10");
        assertThat(bodyCode(second)).isEqualTo("CIRCUIT_BREAKER_OPEN");
        assertThat(elapsedMs).as("fast-fail на открытом circuit breaker").isLessThan(1000);

        warehouseMock.verify(3, postRequestedFor(urlEqualTo("/internal/products/reservations")));
        billingMock.verify(2, postRequestedFor(urlEqualTo("/internal/order/refund")));

        Long secondOrderId = orderRepository.findByUserId(userId + 1).getFirst().getId();
        assertOrderStatus(secondOrderId, Order.OrderStatus.FAILED);
        assertSaga(secondOrderId, OrderSagaState.SagaStatus.COMPENSATED, OrderSagaState.SagaStep.WAREHOUSE_RESERVE);
    }

    @Test
    @DisplayName("после waitDuration -> half-open -> успешная проба -> CLOSED, заказ PLACED")
    void shouldCloseCircuitBreakerAfterHalfOpenProbe() {
        Long userId = nextUserId();
        stubBillingWithdrawOk();
        stubBillingRefundOk();
        stubWarehouseReserveOk();
        stubWarehouseConfirmOk();
        stubWarehouseCancelOk();
        stubDeliveryReserveOk();
        stubDeliveryConfirmOk();
        stubDeliveryCancelOk();

        warehouseCircuitBreaker().transitionToOpenState();
        awaitState(CircuitBreaker.State.HALF_OPEN, Duration.ofSeconds(6));

        ResponseEntity<OrderResponseDto> response = createOrder(userId, OrderResponseDto.class, null);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().orderStatus()).isEqualTo(Order.OrderStatus.PLACED);
        assertThat(warehouseCircuitBreaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        warehouseMock.verify(1, postRequestedFor(urlEqualTo("/internal/products/reservations")));
    }

    @Test
    @DisplayName("исчерпанный лимитер -> RATE_LIMITED после исчерпания ретраев (503), "
            + "запросы до склада не доходят, депозит компенсирован")
    void shouldReturnRateLimitedWhenLimiterExhausted() {
        Long userId = nextUserId();
        stubBillingWithdrawOk();
        stubBillingRefundOk();
        stubWarehouseReserveOk();

        rateLimiterRegistry.replace(WAREHOUSE_CB, RateLimiter.of(WAREHOUSE_CB, RateLimiterConfig.custom()
                .limitForPeriod(1)
                .limitRefreshPeriod(Duration.ofSeconds(60))
                .timeoutDuration(Duration.ZERO)
                .build()));
        consumeSingleWarehousePermit();

        ResponseEntity<String> response = createOrder(userId, String.class, null);

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(bodyCode(response)).isEqualTo("RATE_LIMITED");

        warehouseMock.verify(0, postRequestedFor(urlEqualTo("/internal/products/reservations")));
        billingMock.verify(1, postRequestedFor(urlEqualTo("/internal/order/refund")));

        Long orderId = singleOrderIdByUser(userId);
        assertOrderStatus(orderId, Order.OrderStatus.FAILED);
        assertSaga(orderId, OrderSagaState.SagaStatus.COMPENSATED, OrderSagaState.SagaStep.WAREHOUSE_RESERVE);
    }

    @Test
    @DisplayName("идемпотентность при открытом CB: тот же ключ -> текущее состояние (200), "
            + "новый ключ после восстановления -> 201 PLACED")
    void shouldReplaySameKeyAndSucceedWithNewKeyAfterRecovery() {
        Long userId = nextUserId();
        String idempotencyKey = UUID.randomUUID().toString();
        stubBillingWithdrawOk();
        stubBillingRefundOk();
        stubWarehouseReserveError(500, "Internal error", null);

        warehouseCircuitBreaker().transitionToOpenState();

        ResponseEntity<String> firstAttempt = createOrder(userId, String.class, idempotencyKey);
        assertThat(firstAttempt.getStatusCode().value())
                .as("body=%s", firstAttempt.getBody())
                .isEqualTo(503);
        assertThat(bodyCode(firstAttempt)).isEqualTo("CIRCUIT_BREAKER_OPEN");

        Long failedOrderId = singleOrderIdByUser(userId);
        assertOrderStatus(failedOrderId, Order.OrderStatus.FAILED);

        ResponseEntity<String> replay = createOrder(userId, String.class, idempotencyKey);
        assertThat(replay.getStatusCode().value()).isEqualTo(200);
        assertThat(orderRepository.findByUserId(userId)).hasSize(1);

        warehouseCircuitBreaker().transitionToClosedState();
        stubWarehouseReserveOk();
        stubWarehouseConfirmOk();
        stubWarehouseCancelOk();
        stubDeliveryReserveOk();
        stubDeliveryConfirmOk();
        stubDeliveryCancelOk();

        ResponseEntity<OrderResponseDto> recovered =
                createOrder(userId, OrderResponseDto.class, UUID.randomUUID().toString());

        assertThat(recovered.getStatusCode().value()).isEqualTo(201);
        assertThat(recovered.getBody()).isNotNull();
        assertThat(recovered.getBody().orderStatus()).isEqualTo(Order.OrderStatus.PLACED);
        assertThat(orderRepository.findByUserId(userId)).hasSize(2);
    }

    private void consumeSingleWarehousePermit() {
        try {
            warehouseServiceClient.getReservation(999_999L);
        } catch (WarehouseServiceException expected) {
        }
    }

    private void awaitState(CircuitBreaker.State expected, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (warehouseCircuitBreaker().getState() != expected
                && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for circuit breaker state", e);
            }
        }
        assertThat(warehouseCircuitBreaker().getState()).isEqualTo(expected);
    }

    private String bodyCode(ResponseEntity<String> response) {
        try {
            JsonNode node = objectMapper.readTree(response.getBody());
            return node.path("code").asText();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot parse error response body: " + response.getBody(), e);
        }
    }

    private @NonNull Long nextUserId() {
        return USER_SEQUENCE.incrementAndGet();
    }

    private <T> ResponseEntity<T> createOrder(Long userId, Class<T> responseType, String idempotencyKey) {
        OrderCreateDto dto = new OrderCreateDto(PRICE, "resilience integration test order",
                PRODUCT_ID, QUANTITY, DELIVERY_DATE, SLOT_START, SLOT_END);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(jwtTokenProvider.generateToken(
                "user-" + userId + "@example.com", userId, List.of("USER")));
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return restTemplate.postForEntity("/api/v1/order", new HttpEntity<>(dto, headers), responseType);
    }

    private Long singleOrderIdByUser(Long userId) {
        List<Order> orders = orderRepository.findByUserId(userId);
        assertThat(orders).hasSize(1);
        return orders.getFirst().getId();
    }

    private void assertOrderStatus(Long orderId, Order.OrderStatus expected) {
        Order order = orderRepository.findById(orderId).orElseThrow();
        assertThat(order.getOrderStatus()).isEqualTo(expected);
    }

    private void assertSagaStatus(Long orderId, OrderSagaState.SagaStatus expected) {
        OrderSagaState saga = orderSagaStateRepository.findByOrderId(orderId).orElseThrow();
        assertThat(saga.getSagaStatus()).isEqualTo(expected);
    }

    private void assertSaga(Long orderId, OrderSagaState.SagaStatus status, OrderSagaState.SagaStep step) {
        OrderSagaState saga = orderSagaStateRepository.findByOrderId(orderId).orElseThrow();
        assertThat(saga.getSagaStatus()).isEqualTo(status);
        assertThat(saga.getFailureStep()).isEqualTo(step);
        assertThat(saga.getFailureReason()).isNotBlank();
    }

    private void stubHappyPathExceptWarehouseReserve() {
        stubBillingWithdrawOk();
        stubBillingRefundOk();
        stubWarehouseConfirmOk();
        stubWarehouseCancelOk();
        stubDeliveryReserveOk();
        stubDeliveryConfirmOk();
        stubDeliveryCancelOk();
    }

    private void stubWarehouseReserveSucceedsOnThirdAttempt() {
        warehouseMock.stubFor(post(urlEqualTo("/internal/products/reservations"))
                .inScenario("reserve-flaky")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(500).withBody("Internal error"))
                .willSetStateTo("second-attempt"));
        warehouseMock.stubFor(post(urlEqualTo("/internal/products/reservations"))
                .inScenario("reserve-flaky")
                .whenScenarioStateIs("second-attempt")
                .willReturn(aResponse().withStatus(500).withBody("Internal error"))
                .willSetStateTo("third-attempt"));
        warehouseMock.stubFor(post(urlEqualTo("/internal/products/reservations"))
                .inScenario("reserve-flaky")
                .whenScenarioStateIs("third-attempt")
                .willReturn(reservationOkResponse()));
    }

    private void stubBillingWithdrawOk() {
        billingMock.stubFor(post(urlEqualTo("/internal/order/withdraw"))
                .willReturn(aResponse().withStatus(200)));
    }

    private void stubBillingRefundOk() {
        billingMock.stubFor(post(urlEqualTo("/internal/order/refund"))
                .willReturn(aResponse().withStatus(200)));
    }

    private void stubWarehouseReserveOk() {
        warehouseMock.stubFor(post(urlEqualTo("/internal/products/reservations"))
                .willReturn(reservationOkResponse()));
    }

    private com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder reservationOkResponse() {
        return aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {
                          "orderId": 0,
                          "reservations": [
                            {
                              "id": 1,
                              "orderId": 0,
                              "productId": %d,
                              "sku": "SKU-%d",
                              "quantity": %d,
                              "reservationStatus": "RESERVED",
                              "idempotencyKey": "order-0-p%d",
                              "version": 0
                            }
                          ]
                        }
                        """.formatted(PRODUCT_ID, PRODUCT_ID, QUANTITY, PRODUCT_ID));
    }

    private void stubWarehouseReserveError(int status, String message, String code) {
        String body = code != null
                ? """
                        {"message":"%s","status":%d,"code":"%s"}
                        """.formatted(message, status, code)
                : """
                        {"message":"%s","status":%d}
                        """.formatted(message, status);
        warehouseMock.stubFor(post(urlEqualTo("/internal/products/reservations"))
                .willReturn(aResponse()
                        .withStatus(status)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));
    }

    private void stubWarehouseConfirmOk() {
        warehouseMock.stubFor(post(urlMatching("/internal/products/reservations/\\d+/confirm"))
                .willReturn(aResponse().withStatus(200)));
    }

    private void stubWarehouseCancelOk() {
        warehouseMock.stubFor(post(urlMatching("/internal/products/reservations/\\d+/cancel"))
                .willReturn(aResponse().withStatus(200)));
    }

    private void stubDeliveryReserveOk() {
        deliveryMock.stubFor(post(urlEqualTo("/internal/delivery/reservations"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "reservationId": 55,
                                  "orderId": 0,
                                  "date": "%s",
                                  "slotStart": "%s",
                                  "slotEnd": "%s",
                                  "assignedCourierNumber": 1,
                                  "status": "RESERVED"
                                }
                                """.formatted(DELIVERY_DATE, SLOT_START, SLOT_END))));
    }

    private void stubDeliveryConfirmOk() {
        deliveryMock.stubFor(post(urlMatching("/internal/delivery/reservations/\\d+/confirm"))
                .willReturn(aResponse().withStatus(200)));
    }

    private void stubDeliveryCancelOk() {
        deliveryMock.stubFor(post(urlMatching("/internal/delivery/reservations/\\d+/cancel"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"orderId\":0,\"result\":\"CANCELLED\"}")));
    }
}
