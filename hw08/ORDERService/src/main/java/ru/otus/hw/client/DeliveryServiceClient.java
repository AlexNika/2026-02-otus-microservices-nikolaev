package ru.otus.hw.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import ru.otus.hw.dto.CancelDeliveryResponse;
import ru.otus.hw.dto.DeliveryReservationResponse;
import ru.otus.hw.dto.ReserveDeliveryRequest;
import ru.otus.hw.exception.DeliveryServiceException;
import ru.otus.hw.models.OrderSagaState;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

/**
 * Клиент внутреннего API DELIVERYService для шагов саги создания заказа.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeliveryServiceClient {

    private static final String SERVICE_NAME = "DELIVERYService";

    private static final Set<String> ACTIVE_RESERVE_STATUSES = Set.of("RESERVED", "CONFIRMED");

    private final RestClient deliveryRestClient;

    private final ObjectMapper objectMapper;

    /**
     * Резерв доставки для заказа (прямой шаг саги). Идемпотентен по orderId.
     * Идемпотентный повтор (200) может вернуть резерв в терминальном статусе
     * (CANCELLED/FAILED) — такой результат считается отказом шага, пересоздать бронь нельзя.
     */
    public void reserve(Long orderId, LocalDate date, LocalTime slotStart, LocalTime slotEnd) {
        log.info("Reserving delivery via {}: orderId={}, date={}, slot {}-{}",
                SERVICE_NAME, orderId, date, slotStart, slotEnd);
        try {
            ReserveDeliveryRequest request = new ReserveDeliveryRequest(orderId, date, slotStart, slotEnd);

            DeliveryReservationResponse response = deliveryRestClient.post()
                    .uri("/internal/delivery/reservations")
                    .body(request)
                    .retrieve()
                    .body(DeliveryReservationResponse.class);

            validateReserved(orderId, response);
            log.info("Delivery reserved via {} for orderId={}", SERVICE_NAME, orderId);

        } catch (DeliveryServiceException e) {
            throw e;
        } catch (RestClientResponseException e) {
            String code = DownstreamErrors.extractCode(e, objectMapper);
            String message = DownstreamErrors.describe(e, SERVICE_NAME);
            log.error(message);
            throw new DeliveryServiceException(OrderSagaState.SagaStep.DELIVERY_RESERVE, code, message, e);
        } catch (ResourceAccessException e) {
            String message = SERVICE_NAME + " is unavailable: " + e.getMessage();
            log.error(message, e);
            throw new DeliveryServiceException(OrderSagaState.SagaStep.DELIVERY_RESERVE, null, message, e);
        }
    }

    /**
     * Подтверждение брони доставки (фаза confirm саги). Идемпотентно.
     */
    public void confirm(Long orderId) {
        log.info("Confirming delivery reservation via {}: orderId={}", SERVICE_NAME, orderId);
        try {
            deliveryRestClient.post()
                    .uri("/internal/delivery/reservations/{orderId}/confirm", orderId)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Delivery reservation confirmed via {} for orderId={}", SERVICE_NAME, orderId);
        } catch (RestClientResponseException e) {
            String code = DownstreamErrors.extractCode(e, objectMapper);
            String message = DownstreamErrors.describe(e, SERVICE_NAME);
            log.error(message);
            throw new DeliveryServiceException(OrderSagaState.SagaStep.DELIVERY_CONFIRM, code, message, e);
        } catch (ResourceAccessException e) {
            String message = SERVICE_NAME + " is unavailable: " + e.getMessage();
            log.error(message, e);
            throw new DeliveryServiceException(OrderSagaState.SagaStep.DELIVERY_CONFIRM, null, message, e);
        }
    }

    /**
     * Отмена брони доставки (компенсация саги). Идемпотентна и безопасна для повторных вызовов.
     * Любое значение result (CANCELLED / ALREADY_CANCELLED / NOT_FOUND) считается успешной компенсацией.
     * 409 (бронь уже CONFIRMED) приводит к исключению шага компенсации.
     */
    public CancelDeliveryResponse.CancelDeliveryResult cancel(Long orderId) {
        log.info("Cancelling delivery reservation via {}: orderId={}", SERVICE_NAME, orderId);
        try {
            CancelDeliveryResponse response = deliveryRestClient.post()
                    .uri("/internal/delivery/reservations/{orderId}/cancel", orderId)
                    .retrieve()
                    .body(CancelDeliveryResponse.class);
            CancelDeliveryResponse.CancelDeliveryResult result =
                    response != null ? response.result() : CancelDeliveryResponse.CancelDeliveryResult.NOT_FOUND;
            log.info("Delivery reservation cancel via {} for orderId={} -> {}", SERVICE_NAME, orderId, result);
            return result;
        } catch (RestClientResponseException e) {
            String code = DownstreamErrors.extractCode(e, objectMapper);
            String message = DownstreamErrors.describe(e, SERVICE_NAME);
            log.error(message);
            throw new DeliveryServiceException(OrderSagaState.SagaStep.DELIVERY_CANCEL, code, message, e);
        } catch (ResourceAccessException e) {
            String message = SERVICE_NAME + " is unavailable: " + e.getMessage();
            log.error(message, e);
            throw new DeliveryServiceException(OrderSagaState.SagaStep.DELIVERY_CANCEL, null, message, e);
        }
    }

    /**
     * Получение текущего состояния брони доставки по заказу.
     */
    public DeliveryReservationResponse getReservation(Long orderId) {
        log.info("Fetching delivery reservation via {}: orderId={}", SERVICE_NAME, orderId);
        try {
            return deliveryRestClient.get()
                    .uri("/internal/delivery/reservations/{orderId}", orderId)
                    .retrieve()
                    .body(DeliveryReservationResponse.class);
        } catch (RestClientResponseException e) {
            String code = DownstreamErrors.extractCode(e, objectMapper);
            String message = DownstreamErrors.describe(e, SERVICE_NAME);
            log.error(message);
            throw new DeliveryServiceException(OrderSagaState.SagaStep.DELIVERY_RESERVE, code, message, e);
        } catch (ResourceAccessException e) {
            String message = SERVICE_NAME + " is unavailable: " + e.getMessage();
            log.error(message, e);
            throw new DeliveryServiceException(OrderSagaState.SagaStep.DELIVERY_RESERVE, null, message, e);
        }
    }

    private void validateReserved(Long orderId, DeliveryReservationResponse response) {
        if (response == null) {
            throw new DeliveryServiceException(OrderSagaState.SagaStep.DELIVERY_RESERVE, null,
                    SERVICE_NAME + " returned empty reservation response for orderId=" + orderId);
        }
        String status = response.status();
        if (status == null || !ACTIVE_RESERVE_STATUSES.contains(status)) {
            throw new DeliveryServiceException(OrderSagaState.SagaStep.DELIVERY_RESERVE, null,
                    String.format("%s returned delivery reservation in status %s for orderId=%d, " +
                                    "expected RESERVED/CONFIRMED",
                            SERVICE_NAME, status, orderId));
        }
    }
}
