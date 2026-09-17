package ru.otus.hw.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import ru.otus.hw.config.ResilienceConfig;
import ru.otus.hw.dto.CancelDeliveryResponse;
import ru.otus.hw.dto.DeliveryReservationResponse;
import ru.otus.hw.dto.ReserveDeliveryRequest;
import ru.otus.hw.exception.DeliveryServiceException;
import ru.otus.hw.exception.ErrorCodes;
import ru.otus.hw.models.OrderSagaState;
import ru.otus.hw.models.OrderSagaState.SagaStep;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

/**
 * Клиент внутреннего API DELIVERYService для шагов саги создания заказа.
 *
 * <p>Отказоустойчивость (порядок слоёв: Retry → CircuitBreaker → RateLimiter): транзитные
 * сбои ретраятся, при открытом circuit breaker / исчерпанном лимите fallback переводит отказ в
 * {@link DeliveryServiceException} с кодом {@link ErrorCodes#CIRCUIT_BREAKER_OPEN} /
 * {@link ErrorCodes#RATE_LIMITED} (наружу - 503).
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
     * (CANCELLED/FAILED) - такой результат считается отказом шага, пересоздать бронь нельзя.
     */
    @CircuitBreaker(name = ResilienceConfig.DELIVERY, fallbackMethod = "reserveFallback")
    @Retry(name = ResilienceConfig.DELIVERY)
    @RateLimiter(name = ResilienceConfig.DELIVERY, fallbackMethod = "reserveFallback")
    public void reserve(Long orderId, Long userId, LocalDate date, LocalTime slotStart, LocalTime slotEnd) {
        log.info("Reserving delivery via {}: orderId={}, date={}, slot {}-{}",
                SERVICE_NAME, orderId, date, slotStart, slotEnd);
        try {
            ReserveDeliveryRequest request = new ReserveDeliveryRequest(orderId, date, slotStart, slotEnd, userId);

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
            log.error(message);
            throw new DeliveryServiceException(OrderSagaState.SagaStep.DELIVERY_RESERVE, null, message, e);
        }
    }

    /**
     * Подтверждение брони доставки (фаза confirm саги). Идемпотентно.
     */
    @CircuitBreaker(name = ResilienceConfig.DELIVERY, fallbackMethod = "confirmFallback")
    @Retry(name = ResilienceConfig.DELIVERY)
    @RateLimiter(name = ResilienceConfig.DELIVERY, fallbackMethod = "confirmFallback")
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
            log.error(message);
            throw new DeliveryServiceException(OrderSagaState.SagaStep.DELIVERY_CONFIRM, null, message, e);
        }
    }

    /**
     * Отмена брони доставки (компенсация саги). Идемпотентна и безопасна для повторных вызовов.
     * Любое значение result (CANCELLED / ALREADY_CANCELLED / NOT_FOUND) считается успешной компенсацией.
     * 409 (бронь уже CONFIRMED) приводит к исключению шага компенсации.
     */
    @CircuitBreaker(name = ResilienceConfig.DELIVERY, fallbackMethod = "cancelFallback")
    @Retry(name = ResilienceConfig.DELIVERY)
    @RateLimiter(name = ResilienceConfig.DELIVERY, fallbackMethod = "cancelFallback")
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
            log.error(message);
            throw new DeliveryServiceException(OrderSagaState.SagaStep.DELIVERY_CANCEL, null, message, e);
        }
    }

    /**
     * Получение текущего состояния брони доставки по заказу.
     */
    @CircuitBreaker(name = ResilienceConfig.DELIVERY, fallbackMethod = "getReservationFallback")
    @Retry(name = ResilienceConfig.DELIVERY)
    @RateLimiter(name = ResilienceConfig.DELIVERY, fallbackMethod = "getReservationFallback")
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
            log.error(message);
            throw new DeliveryServiceException(OrderSagaState.SagaStep.DELIVERY_RESERVE, null, message, e);
        }
    }

    /**
     * Fallback для {@link #reserve}: открытый circuit breaker / исчерпанный лимит
     * переводятся в машинные коды, прочие исключения пробрасываются как есть.
     */
    private void reserveFallback(Long orderId, Long userId, LocalDate date, LocalTime slotStart, LocalTime slotEnd,
                                 Throwable throwable) throws Throwable {
        throw resilienceFailure(SagaStep.DELIVERY_RESERVE, String.format("reserve for orderId=%d", orderId),
                throwable);
    }

    /**
     * Fallback для {@link #confirm}: см. {@link #reserveFallback}.
     */
    private void confirmFallback(Long orderId, Throwable throwable) throws Throwable {
        throw resilienceFailure(SagaStep.DELIVERY_CONFIRM, String.format("confirm for orderId=%d", orderId),
                throwable);
    }

    /**
     * Fallback для {@link #cancel}: см. {@link #reserveFallback}.
     */
    private CancelDeliveryResponse.CancelDeliveryResult cancelFallback(Long orderId, Throwable throwable)
            throws Throwable {
        throw resilienceFailure(SagaStep.DELIVERY_CANCEL, String.format("cancel for orderId=%d", orderId),
                throwable);
    }

    /**
     * Fallback для {@link #getReservation}: см. {@link #reserveFallback}.
     */
    private DeliveryReservationResponse getReservationFallback(Long orderId, Throwable throwable) throws Throwable {
        throw resilienceFailure(SagaStep.DELIVERY_RESERVE, String.format("getReservation for orderId=%d", orderId),
                throwable);
    }

    /**
     * {@link CallNotPermittedException} (circuit breaker открыт) → код
     * {@link ErrorCodes#CIRCUIT_BREAKER_OPEN}; {@link RequestNotPermitted} (лимит исчерпан)
     * → код {@link ErrorCodes#RATE_LIMITED}; остальное - проброс исходного исключения
     * (его классифицируют Retry/CircuitBreaker по {@link DownstreamFaults}).
     */
    private @NonNull Throwable resilienceFailure(@NonNull SagaStep step, @NonNull String operation,
                                                 @NonNull Throwable throwable) {
        if (throwable instanceof CallNotPermittedException) {
            String message = String.format("%s circuit breaker is open, %s rejected: %s",
                    SERVICE_NAME, operation, throwable.getMessage());
            log.error(message);
            return new DeliveryServiceException(step, ErrorCodes.CIRCUIT_BREAKER_OPEN, message, throwable);
        }
        if (throwable instanceof RequestNotPermitted) {
            String message = String.format("%s rate limit exhausted, %s rejected: %s",
                    SERVICE_NAME, operation, throwable.getMessage());
            log.error(message);
            return new DeliveryServiceException(step, ErrorCodes.RATE_LIMITED, message, throwable);
        }
        return throwable;
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
