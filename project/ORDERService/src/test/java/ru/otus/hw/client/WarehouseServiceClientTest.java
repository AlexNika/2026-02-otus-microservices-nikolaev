package ru.otus.hw.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.otus.hw.dto.ProductReservationListResponseDto;
import ru.otus.hw.dto.ProductReservationResponseDto;
import ru.otus.hw.dto.ReservationStatus;
import ru.otus.hw.exception.WarehouseServiceException;
import ru.otus.hw.models.OrderSagaState;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WarehouseServiceClientTest {

    private static final Long ORDER_ID = 100L;

    private static final Long PRODUCT_ID = 11L;

    private static final String IDEMPOTENCY_KEY = "order-" + ORDER_ID + "-p" + PRODUCT_ID;

    @Mock
    private RestClient warehouseRestClient;

    private WarehouseServiceClient client;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void setUp() {
        client = new WarehouseServiceClient(warehouseRestClient, objectMapper);
    }

    private ProductReservationListResponseDto reservationList(ReservationStatus status) {
        ProductReservationResponseDto reservation = ProductReservationResponseDto.builder()
                .id(1L)
                .orderId(ORDER_ID)
                .productId(PRODUCT_ID)
                .quantity(3)
                .reservationStatus(status)
                .build();
        return ProductReservationListResponseDto.builder()
                .orderId(ORDER_ID)
                .reservations(List.of(reservation))
                .build();
    }

    private void stubReserveResponse(Object responseOrException) {
        RestClient.RequestBodyUriSpec postSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(warehouseRestClient.post()).thenReturn(postSpec);
        when(postSpec.uri(anyString())).thenReturn(bodySpec);
        when(bodySpec.body(org.mockito.ArgumentMatchers.<Object>any())).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        if (responseOrException instanceof RuntimeException exception) {
            when(responseSpec.body(eq(ProductReservationListResponseDto.class))).thenThrow(exception);
        } else {
            when(responseSpec.body(eq(ProductReservationListResponseDto.class)))
                    .thenReturn((ProductReservationListResponseDto) responseOrException);
        }
    }

    @Test
    @DisplayName("reserve: статус RESERVED - успех без исключения")
    void shouldSucceedReserveWhenStatusReserved() {
        stubReserveResponse(reservationList(ReservationStatus.RESERVED));

        assertDoesNotThrow(() -> client.reserve(ORDER_ID, PRODUCT_ID, 3, IDEMPOTENCY_KEY));
    }

    @Test
    @DisplayName("reserve: идемпотентный повтор вернул RELEASED - шаг считается отказом")
    void shouldThrowWhenReserveReturnsReleased() {
        stubReserveResponse(reservationList(ReservationStatus.RELEASED));

        WarehouseServiceException ex = assertThrows(WarehouseServiceException.class,
                () -> client.reserve(ORDER_ID, PRODUCT_ID, 3, IDEMPOTENCY_KEY));

        assertThat(ex.getStep()).isEqualTo(OrderSagaState.SagaStep.WAREHOUSE_RESERVE);
        assertThat(ex.getMessage()).contains(ReservationStatus.RELEASED.name());
    }

    @Test
    @DisplayName("reserve: 409 INSUFFICIENT_STOCK - исключение с кодом ошибки downstream")
    void shouldThrowWithCodeWhenReserveConflict() {
        String errorBody = "{\"message\":\"Not enough stock\",\"status\":409,\"code\":\"INSUFFICIENT_STOCK\"}";
        HttpClientErrorException conflict = HttpClientErrorException.create(
                HttpStatusCode.valueOf(409), "Conflict", new HttpHeaders(),
                errorBody.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        stubReserveResponse(conflict);

        WarehouseServiceException ex = assertThrows(WarehouseServiceException.class,
                () -> client.reserve(ORDER_ID, PRODUCT_ID, 3, IDEMPOTENCY_KEY));

        assertThat(ex.getCode()).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(ex.getStep()).isEqualTo(OrderSagaState.SagaStep.WAREHOUSE_RESERVE);
    }

    private void stubCancel(Object responseOrException) {
        RestClient.RequestBodyUriSpec postSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(warehouseRestClient.post()).thenReturn(postSpec);
        when(postSpec.uri(anyString(), any(Object[].class))).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        if (responseOrException instanceof RuntimeException exception) {
            when(responseSpec.toBodilessEntity()).thenThrow(exception);
        } else {
            when(responseSpec.toBodilessEntity()).thenReturn(ResponseEntity.ok().build());
        }
    }

    @Test
    @DisplayName("cancel: 404 (резервов нет) - тихий успех компенсации")
    void shouldSucceedCancelWhenNotFound() {
        HttpClientErrorException notFound = HttpClientErrorException.create(
                HttpStatusCode.valueOf(404), "Not Found", new HttpHeaders(),
                "{\"message\":\"not found\"}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        stubCancel(notFound);

        assertDoesNotThrow(() -> client.cancel(ORDER_ID));
    }

    @Test
    @DisplayName("cancel: 400 (бронь CONFIRMED) - исключение шага компенсации")
    void shouldThrowWhenCancelReturnsBadRequest() {
        HttpClientErrorException badRequest = HttpClientErrorException.create(
                HttpStatusCode.valueOf(400), "Bad Request", new HttpHeaders(),
                "{\"message\":\"Cannot cancel CONFIRMED\"}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        stubCancel(badRequest);

        WarehouseServiceException ex = assertThrows(WarehouseServiceException.class,
                () -> client.cancel(ORDER_ID));

        assertThat(ex.getStep()).isEqualTo(OrderSagaState.SagaStep.WAREHOUSE_CANCEL);
    }

    @Test
    @DisplayName("confirm: 200 - успех без исключения")
    void shouldSucceedConfirm() {
        stubCancel(ResponseEntity.ok().build());

        assertDoesNotThrow(() -> client.confirm(ORDER_ID));
    }
}
