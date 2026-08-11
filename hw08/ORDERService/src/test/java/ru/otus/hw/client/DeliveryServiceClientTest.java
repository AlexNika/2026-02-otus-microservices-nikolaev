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
import ru.otus.hw.dto.CancelDeliveryResponse;
import ru.otus.hw.dto.CancelDeliveryResponse.CancelDeliveryResult;
import ru.otus.hw.dto.DeliveryReservationResponse;
import ru.otus.hw.exception.DeliveryServiceException;
import ru.otus.hw.models.OrderSagaState;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeliveryServiceClientTest {

    private static final Long ORDER_ID = 100L;

    private static final LocalDate DATE = LocalDate.now().plusDays(1);

    private static final LocalTime SLOT_START = LocalTime.of(10, 0);

    private static final LocalTime SLOT_END = LocalTime.of(12, 0);

    @Mock
    private RestClient deliveryRestClient;

    private DeliveryServiceClient client;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void setUp() {
        client = new DeliveryServiceClient(deliveryRestClient, objectMapper);
    }

    private DeliveryReservationResponse reservation(String status) {
        return DeliveryReservationResponse.builder()
                .reservationId(1L)
                .orderId(ORDER_ID)
                .date(DATE)
                .slotStart(SLOT_START)
                .slotEnd(SLOT_END)
                .assignedCourierNumber(1)
                .status(status)
                .build();
    }

    private void stubReserve(Object responseOrException) {
        RestClient.RequestBodyUriSpec postSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(deliveryRestClient.post()).thenReturn(postSpec);
        when(postSpec.uri(anyString())).thenReturn(bodySpec);
        when(bodySpec.body(org.mockito.ArgumentMatchers.<Object>any())).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        if (responseOrException instanceof RuntimeException exception) {
            when(responseSpec.body(eq(DeliveryReservationResponse.class))).thenThrow(exception);
        } else {
            when(responseSpec.body(eq(DeliveryReservationResponse.class)))
                    .thenReturn((DeliveryReservationResponse) responseOrException);
        }
    }

    @Test
    @DisplayName("reserve: статус RESERVED - успех без исключения")
    void shouldSucceedReserveWhenStatusReserved() {
        stubReserve(reservation("RESERVED"));

        assertDoesNotThrow(() -> client.reserve(ORDER_ID, DATE, SLOT_START, SLOT_END));
    }

    @Test
    @DisplayName("reserve: идемпотентный повтор вернул CANCELLED - шаг считается отказом")
    void shouldThrowWhenReserveReturnsCancelled() {
        stubReserve(reservation("CANCELLED"));

        DeliveryServiceException ex = assertThrows(DeliveryServiceException.class,
                () -> client.reserve(ORDER_ID, DATE, SLOT_START, SLOT_END));

        assertThat(ex.getStep()).isEqualTo(OrderSagaState.SagaStep.DELIVERY_RESERVE);
        assertThat(ex.getMessage()).contains("CANCELLED");
    }

    @Test
    @DisplayName("reserve: 409 DELIVERY_NO_FREE_COURIER - исключение с кодом ошибки downstream")
    void shouldThrowWithCodeWhenReserveConflict() {
        String errorBody = "{\"message\":\"No free courier\",\"status\":409,\"code\":\"DELIVERY_NO_FREE_COURIER\"}";
        HttpClientErrorException conflict = HttpClientErrorException.create(
                HttpStatusCode.valueOf(409), "Conflict", new HttpHeaders(),
                errorBody.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        stubReserve(conflict);

        DeliveryServiceException ex = assertThrows(DeliveryServiceException.class,
                () -> client.reserve(ORDER_ID, DATE, SLOT_START, SLOT_END));

        assertThat(ex.getCode()).isEqualTo("DELIVERY_NO_FREE_COURIER");
        assertThat(ex.getStep()).isEqualTo(OrderSagaState.SagaStep.DELIVERY_RESERVE);
    }

    private void stubCancelByOrderId(Object responseOrException) {
        RestClient.RequestBodyUriSpec postSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(deliveryRestClient.post()).thenReturn(postSpec);
        when(postSpec.uri(anyString(), any(Object[].class))).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        if (responseOrException instanceof RuntimeException exception) {
            when(responseSpec.body(eq(CancelDeliveryResponse.class))).thenThrow(exception);
        } else {
            when(responseSpec.body(eq(CancelDeliveryResponse.class)))
                    .thenReturn((CancelDeliveryResponse) responseOrException);
        }
    }

    @Test
    @DisplayName("cancel: 200 NOT_FOUND - успешная компенсация, возвращает NOT_FOUND")
    void shouldReturnNotFoundWhenCancelUnknown() {
        stubCancelByOrderId(CancelDeliveryResponse.builder()
                .orderId(ORDER_ID)
                .result(CancelDeliveryResult.NOT_FOUND)
                .build());

        CancelDeliveryResult result = client.cancel(ORDER_ID);

        assertEquals(CancelDeliveryResult.NOT_FOUND, result);
    }

    @Test
    @DisplayName("cancel: 200 CANCELLED - успешная компенсация")
    void shouldReturnCancelledWhenCancelSuccess() {
        stubCancelByOrderId(CancelDeliveryResponse.builder()
                .orderId(ORDER_ID)
                .result(CancelDeliveryResult.CANCELLED)
                .build());

        CancelDeliveryResult result = client.cancel(ORDER_ID);

        assertEquals(CancelDeliveryResult.CANCELLED, result);
    }

    @Test
    @DisplayName("cancel: 409 (бронь CONFIRMED) - исключение шага компенсации")
    void shouldThrowWhenCancelReturnsConflict() {
        String errorBody = "{\"message\":\"Cannot cancel CONFIRMED\",\"status\":409,"
                + "\"code\":\"DELIVERY_RESERVATION_STATE_CONFLICT\"}";
        HttpClientErrorException conflict = HttpClientErrorException.create(
                HttpStatusCode.valueOf(409), "Conflict", new HttpHeaders(),
                errorBody.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        stubCancelByOrderId(conflict);

        DeliveryServiceException ex = assertThrows(DeliveryServiceException.class,
                () -> client.cancel(ORDER_ID));

        assertThat(ex.getStep()).isEqualTo(OrderSagaState.SagaStep.DELIVERY_CANCEL);
        assertThat(ex.getCode()).isEqualTo("DELIVERY_RESERVATION_STATE_CONFLICT");
    }

    @Test
    @DisplayName("confirm: 200 - успех без исключения")
    void shouldSucceedConfirm() {
        RestClient.RequestBodyUriSpec postSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(deliveryRestClient.post()).thenReturn(postSpec);
        when(postSpec.uri(anyString(), any(Object[].class))).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toBodilessEntity()).thenReturn(ResponseEntity.ok().build());

        assertDoesNotThrow(() -> client.confirm(ORDER_ID));
    }
}
