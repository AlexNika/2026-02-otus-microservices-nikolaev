package ru.otus.hw.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
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
import org.junit.jupiter.api.extension.RegisterExtension;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import ru.otus.hw.dto.OrderCreateDto;
import ru.otus.hw.dto.OrderResponseDto;
import ru.otus.hw.models.Order;
import ru.otus.hw.models.OrderSagaState;
import ru.otus.hw.producer.NotificationEventPublisher;
import ru.otus.hw.repository.OrderRepository;
import ru.otus.hw.repository.OrderSagaStateRepository;

import java.net.http.HttpClient;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Интеграционные тесты саги создания заказа: Testcontainers (PostgreSQL + Flyway-миграции)
 * и WireMock-заглушки BILLING/WAREHOUSE/DELIVERY сервисов.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import(OrderSagaIntegrationTest.HttpClientTestConfig.class)
class OrderSagaIntegrationTest {

    /**
     * WireMock (Jetty) не отвечает на HTTP/2 upgrade JDK HttpClient — для тестовых RestClient
     * принудительно включаем HTTP/1.1. Прод-конфигурация не затронута.
     */
    @TestConfiguration
    static class HttpClientTestConfig {

        @Bean
        RestClientCustomizer http11RequestFactory() {
            return builder -> builder.requestFactory(new JdkClientHttpRequestFactory(
                    HttpClient.newBuilder()
                            .version(HttpClient.Version.HTTP_1_1)
                            .build()));
        }
    }

    private static final AtomicLong USER_SEQUENCE = new AtomicLong(9000);

    private static final Long PRODUCT_ID = 11L;

    private static final Integer QUANTITY = 3;

    private static final BigDecimal PRICE = new BigDecimal("250.0000");

    private static final LocalDate DELIVERY_DATE = LocalDate.now().plusDays(1);

    private static final LocalTime SLOT_START = LocalTime.of(10, 0);

    private static final LocalTime SLOT_END = LocalTime.of(12, 0);

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
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.billing-service-url", billingMock::baseUrl);
        registry.add("app.warehouse-service-url", warehouseMock::baseUrl);
        registry.add("app.delivery-service-url", deliveryMock::baseUrl);
        registry.add("app.internal-api-key", () -> "test-internal-api-key");
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderSagaStateRepository orderSagaStateRepository;

    @MockitoBean
    private NotificationEventPublisher notificationEventPublisher;

    @Test
    @DisplayName("happy path: все шаги + confirm - заказ PLACED, сага CONFIRMED, оба confirm вызваны")
    void shouldPlaceOrderWhenAllStepsSucceed() {
        Long userId = nextUserId();
        stubBillingWithdrawOk();
        stubBillingRefundOk();
        stubWarehouseReserveOk("RESERVED");
        stubWarehouseConfirmOk();
        stubWarehouseCancelOk();
        stubDeliveryReserveOk();
        stubDeliveryConfirmOk();
        stubDeliveryCancelOk();

        ResponseEntity<OrderResponseDto> response = createOrder(userId, OrderResponseDto.class);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().orderStatus()).isEqualTo(Order.OrderStatus.PLACED);

        Long orderId = response.getBody().id();
        assertOrderStatus(orderId, Order.OrderStatus.PLACED);
        assertSagaStatus(orderId, OrderSagaState.SagaStatus.CONFIRMED);

        billingMock.verify(1, postRequestedFor(urlEqualTo("/internal/order/withdraw")));
        billingMock.verify(0, postRequestedFor(urlEqualTo("/internal/order/refund")));
        warehouseMock.verify(1, postRequestedFor(urlEqualTo("/internal/products/reservations")));
        warehouseMock.verify(1, postRequestedFor(urlMatching("/internal/products/reservations/\\d+/confirm")));
        warehouseMock.verify(0, postRequestedFor(urlMatching("/internal/products/reservations/\\d+/cancel")));
        deliveryMock.verify(1, postRequestedFor(urlEqualTo("/internal/delivery/reservations")));
        deliveryMock.verify(1, postRequestedFor(urlMatching("/internal/delivery/reservations/\\d+/confirm")));
        deliveryMock.verify(0, postRequestedFor(urlMatching("/internal/delivery/reservations/\\d+/cancel")));
    }

    @Test
    @DisplayName("409 BILLING_INSUFFICIENT_FUNDS - заказ FAILED, компенсаций нет")
    void shouldFailOrderWhenBillingInsufficientFunds() {
        Long userId = nextUserId();
        stubBillingWithdrawError(409, "Insufficient funds", "BILLING_INSUFFICIENT_FUNDS");

        ResponseEntity<String> response = createOrder(userId, String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(502);

        Long orderId = singleOrderIdByUser(userId);
        assertOrderStatus(orderId, Order.OrderStatus.FAILED);
        assertSaga(orderId, OrderSagaState.SagaStatus.COMPENSATED, OrderSagaState.SagaStep.BILLING_WITHDRAW);

        billingMock.verify(0, postRequestedFor(urlEqualTo("/internal/order/refund")));
        warehouseMock.verify(0, postRequestedFor(urlEqualTo("/internal/products/reservations")));
        deliveryMock.verify(0, postRequestedFor(urlEqualTo("/internal/delivery/reservations")));
    }

    @Test
    @DisplayName("404 BILLING_ACCOUNT_NOT_FOUND - заказ FAILED, компенсаций нет")
    void shouldFailOrderWhenBillingAccountNotFound() {
        Long userId = nextUserId();
        stubBillingWithdrawError(404, "Account not found", "BILLING_ACCOUNT_NOT_FOUND");

        ResponseEntity<String> response = createOrder(userId, String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(502);

        Long orderId = singleOrderIdByUser(userId);
        assertOrderStatus(orderId, Order.OrderStatus.FAILED);
        assertSaga(orderId, OrderSagaState.SagaStatus.COMPENSATED, OrderSagaState.SagaStep.BILLING_WITHDRAW);

        billingMock.verify(0, postRequestedFor(urlEqualTo("/internal/order/refund")));
        warehouseMock.verify(0, postRequestedFor(urlEqualTo("/internal/products/reservations")));
        deliveryMock.verify(0, postRequestedFor(urlEqualTo("/internal/delivery/reservations")));
    }

    @Test
    @DisplayName("409 INSUFFICIENT_STOCK на складе - refund вызван, доставка не вызывалась")
    void shouldFailOrderWhenInsufficientStock() {
        Long userId = nextUserId();
        stubBillingWithdrawOk();
        stubBillingRefundOk();
        stubWarehouseReserveError(409, "Not enough stock", "INSUFFICIENT_STOCK");

        ResponseEntity<String> response = createOrder(userId, String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(409);

        Long orderId = singleOrderIdByUser(userId);
        assertOrderStatus(orderId, Order.OrderStatus.FAILED);
        assertSaga(orderId, OrderSagaState.SagaStatus.COMPENSATED, OrderSagaState.SagaStep.WAREHOUSE_RESERVE);

        billingMock.verify(1, postRequestedFor(urlEqualTo("/internal/order/refund")));
        warehouseMock.verify(1, postRequestedFor(urlEqualTo("/internal/products/reservations")));
        warehouseMock.verify(0, postRequestedFor(urlMatching("/internal/products/reservations/\\d+/cancel")));
        deliveryMock.verify(0, postRequestedFor(urlEqualTo("/internal/delivery/reservations")));
    }

    @Test
    @DisplayName("409 DELIVERY_NO_FREE_COURIER - cancel склада + refund, сага COMPENSATED")
    void shouldFailOrderWhenNoFreeCourier() {
        Long userId = nextUserId();
        stubBillingWithdrawOk();
        stubBillingRefundOk();
        stubWarehouseReserveOk("RESERVED");
        stubWarehouseCancelOk();
        stubDeliveryReserveError(409, "No free courier", "DELIVERY_NO_FREE_COURIER");

        ResponseEntity<String> response = createOrder(userId, String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(409);

        Long orderId = singleOrderIdByUser(userId);
        assertOrderStatus(orderId, Order.OrderStatus.FAILED);
        assertSaga(orderId, OrderSagaState.SagaStatus.COMPENSATED, OrderSagaState.SagaStep.DELIVERY_RESERVE);

        warehouseMock.verify(1, postRequestedFor(urlMatching("/internal/products/reservations/\\d+/cancel")));
        billingMock.verify(1, postRequestedFor(urlEqualTo("/internal/order/refund")));
        deliveryMock.verify(0, postRequestedFor(urlMatching("/internal/delivery/reservations/\\d+/cancel")));
        deliveryMock.verify(0, postRequestedFor(urlMatching("/internal/delivery/reservations/\\d+/confirm")));
        warehouseMock.verify(0, postRequestedFor(urlMatching("/internal/products/reservations/\\d+/confirm")));
    }

    @Test
    @DisplayName("200 RELEASED на reserve склада - заказ FAILED, refund выполнен")
    void shouldFailOrderWhenWarehouseReserveReturnsReleased() {
        Long userId = nextUserId();
        stubBillingWithdrawOk();
        stubBillingRefundOk();
        stubWarehouseReserveOk("RELEASED");
        stubWarehouseCancelOk();

        ResponseEntity<String> response = createOrder(userId, String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(409);

        Long orderId = singleOrderIdByUser(userId);
        assertOrderStatus(orderId, Order.OrderStatus.FAILED);
        assertSaga(orderId, OrderSagaState.SagaStatus.COMPENSATED, OrderSagaState.SagaStep.WAREHOUSE_RESERVE);

        billingMock.verify(1, postRequestedFor(urlEqualTo("/internal/order/refund")));
        deliveryMock.verify(0, postRequestedFor(urlEqualTo("/internal/delivery/reservations")));
    }

    @Test
    @DisplayName("cancel склада вернул 404 при компенсации - сага COMPENSATED")
    void shouldMarkSagaCompensatedWhenWarehouseCancelReturns404() {
        Long userId = nextUserId();
        stubBillingWithdrawOk();
        stubBillingRefundOk();
        stubWarehouseReserveOk("RESERVED");
        stubWarehouseCancelNotFound();
        stubDeliveryReserveError(409, "No free courier", "DELIVERY_NO_FREE_COURIER");

        ResponseEntity<String> response = createOrder(userId, String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(409);

        Long orderId = singleOrderIdByUser(userId);
        assertOrderStatus(orderId, Order.OrderStatus.FAILED);
        assertSaga(orderId, OrderSagaState.SagaStatus.COMPENSATED, OrderSagaState.SagaStep.DELIVERY_RESERVE);

        warehouseMock.verify(1, postRequestedFor(urlMatching("/internal/products/reservations/\\d+/cancel")));
        billingMock.verify(1, postRequestedFor(urlEqualTo("/internal/order/refund")));
    }

    @Test
    @DisplayName("500 на cancel склада при компенсации - сага COMPENSATION_FAILED, заказ FAILED")
    void shouldMarkSagaCompensationFailedWhenCancelFails() {
        Long userId = nextUserId();
        stubBillingWithdrawOk();
        stubBillingRefundOk();
        stubWarehouseReserveOk("RESERVED");
        stubWarehouseCancelError(500, "Internal error");
        stubDeliveryReserveError(409, "No free courier", "DELIVERY_NO_FREE_COURIER");

        ResponseEntity<String> response = createOrder(userId, String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(409);

        Long orderId = singleOrderIdByUser(userId);
        assertOrderStatus(orderId, Order.OrderStatus.FAILED);
        assertSaga(orderId, OrderSagaState.SagaStatus.COMPENSATION_FAILED, OrderSagaState.SagaStep.DELIVERY_RESERVE);

        billingMock.verify(1, postRequestedFor(urlEqualTo("/internal/order/refund")));
    }

    // ============================== helpers ==============================

    private Long nextUserId() {
        return USER_SEQUENCE.incrementAndGet();
    }

    private <T> ResponseEntity<T> createOrder(Long userId, Class<T> responseType) {
        OrderCreateDto dto = new OrderCreateDto(userId, PRICE, "integration test order",
                PRODUCT_ID, QUANTITY, DELIVERY_DATE, SLOT_START, SLOT_END);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
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

    private void stubBillingWithdrawOk() {
        billingMock.stubFor(post(urlEqualTo("/internal/order/withdraw"))
                .willReturn(aResponse().withStatus(200)));
    }

    private void stubBillingWithdrawError(int status, String message, String code) {
        billingMock.stubFor(post(urlEqualTo("/internal/order/withdraw"))
                .willReturn(aResponse()
                        .withStatus(status)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"message":"%s","status":%d,"code":"%s"}
                                """.formatted(message, status, code))));
    }

    private void stubBillingRefundOk() {
        billingMock.stubFor(post(urlEqualTo("/internal/order/refund"))
                .willReturn(aResponse().withStatus(200)));
    }

    private void stubWarehouseReserveOk(String reservationStatus) {
        warehouseMock.stubFor(post(urlEqualTo("/internal/products/reservations"))
                .willReturn(aResponse()
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
                                      "reservationStatus": "%s",
                                      "idempotencyKey": 1,
                                      "version": 0
                                    }
                                  ]
                                }
                                """.formatted(PRODUCT_ID, PRODUCT_ID, QUANTITY, reservationStatus))));
    }

    private void stubWarehouseReserveError(int status, String message, String code) {
        warehouseMock.stubFor(post(urlEqualTo("/internal/products/reservations"))
                .willReturn(aResponse()
                        .withStatus(status)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"message":"%s","status":%d,"code":"%s"}
                                """.formatted(message, status, code))));
    }

    private void stubWarehouseConfirmOk() {
        warehouseMock.stubFor(post(urlMatching("/internal/products/reservations/\\d+/confirm"))
                .willReturn(aResponse().withStatus(200)));
    }

    private void stubWarehouseCancelOk() {
        warehouseMock.stubFor(post(urlMatching("/internal/products/reservations/\\d+/cancel"))
                .willReturn(aResponse().withStatus(200)));
    }

    private void stubWarehouseCancelNotFound() {
        warehouseMock.stubFor(post(urlMatching("/internal/products/reservations/\\d+/cancel"))
                .willReturn(aResponse()
                        .withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"message\":\"Reservations not found\",\"status\":404}")));
    }

    private void stubWarehouseCancelError(int status, String message) {
        warehouseMock.stubFor(post(urlMatching("/internal/products/reservations/\\d+/cancel"))
                .willReturn(aResponse()
                        .withStatus(status)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"message":"%s","status":%d}
                                """.formatted(message, status))));
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

    private void stubDeliveryReserveError(int status, String message, String code) {
        deliveryMock.stubFor(post(urlEqualTo("/internal/delivery/reservations"))
                .willReturn(aResponse()
                        .withStatus(status)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"message":"%s","status":%d,"code":"%s"}
                                """.formatted(message, status, code))));
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
