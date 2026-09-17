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
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import ru.otus.hw.config.ResilienceConfig;
import ru.otus.hw.dto.ProductReservationCreateRequestDto;
import ru.otus.hw.dto.ProductReservationListResponseDto;
import ru.otus.hw.dto.ProductReservationResponseDto;
import ru.otus.hw.dto.ReservationItemRequestDto;
import ru.otus.hw.dto.ReservationStatus;
import ru.otus.hw.exception.ErrorCodes;
import ru.otus.hw.exception.WarehouseServiceException;
import ru.otus.hw.models.OrderSagaState;
import ru.otus.hw.models.OrderSagaState.SagaStep;

import java.util.List;

/**
 * Клиент внутреннего API WAREHOUSEService для шагов саги создания заказа.
 *
 * <p>Отказоустойчивость (порядок слоёв: Retry → CircuitBreaker → RateLimiter): транзитные
 * сбои ретраятся, при открытом circuit breaker / исчерпанном лимите fallback переводит отказ в
 * {@link WarehouseServiceException} с кодом {@link ErrorCodes#CIRCUIT_BREAKER_OPEN} /
 * {@link ErrorCodes#RATE_LIMITED} (наружу - 503).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WarehouseServiceClient {

    private static final String SERVICE_NAME = "WAREHOUSEService";

    private final RestClient warehouseRestClient;

    private final ObjectMapper objectMapper;

    /**
     * Резерв товара для заказа (прямой шаг саги). Идемпотентен по idempotencyKey.
     * Проверяет статус резерва в теле ответа: идемпотентный повтор может вернуть 200,
     * но резерв в статусе, отличном от RESERVED/CONFIRMED (например RELEASED), считается отказом шага.
     */
    @CircuitBreaker(name = ResilienceConfig.WAREHOUSE, fallbackMethod = "reserveFallback")
    @Retry(name = ResilienceConfig.WAREHOUSE)
    @RateLimiter(name = ResilienceConfig.WAREHOUSE, fallbackMethod = "reserveFallback")
    public void reserve(Long orderId, Long productId, Integer quantity, String idempotencyKey) {
        log.info("Reserving product via {}: orderId={}, productId={}, quantity={}, idempotencyKey={}",
                SERVICE_NAME, orderId, productId, quantity, idempotencyKey);
        try {
            ReservationItemRequestDto item = new ReservationItemRequestDto(productId, quantity, idempotencyKey);
            ProductReservationCreateRequestDto request = new ProductReservationCreateRequestDto(orderId, List.of(item));

            ProductReservationListResponseDto response = warehouseRestClient.post()
                    .uri("/internal/products/reservations")
                    .body(request)
                    .retrieve()
                    .body(ProductReservationListResponseDto.class);

            validateReserved(orderId, response);
            log.info("Product reserved via {} for orderId={}", SERVICE_NAME, orderId);

        } catch (WarehouseServiceException e) {
            throw e;
        } catch (RestClientResponseException e) {
            String code = DownstreamErrors.extractCode(e, objectMapper);
            String message = DownstreamErrors.describe(e, SERVICE_NAME);
            log.error(message);
            throw new WarehouseServiceException(OrderSagaState.SagaStep.WAREHOUSE_RESERVE, code, message, e);
        } catch (ResourceAccessException e) {
            String message = SERVICE_NAME + " is unavailable: " + e.getMessage();
            log.error(message);
            throw new WarehouseServiceException(OrderSagaState.SagaStep.WAREHOUSE_RESERVE, null, message, e);
        }
    }

    /**
     * Подтверждение резервов заказа (фаза confirm саги). Идемпотентно.
     */
    @CircuitBreaker(name = ResilienceConfig.WAREHOUSE, fallbackMethod = "confirmFallback")
    @Retry(name = ResilienceConfig.WAREHOUSE)
    @RateLimiter(name = ResilienceConfig.WAREHOUSE, fallbackMethod = "confirmFallback")
    public void confirm(Long orderId) {
        log.info("Confirming product reservations via {}: orderId={}", SERVICE_NAME, orderId);
        try {
            warehouseRestClient.post()
                    .uri("/internal/products/reservations/{orderId}/confirm", orderId)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Product reservations confirmed via {} for orderId={}", SERVICE_NAME, orderId);
        } catch (RestClientResponseException e) {
            String code = DownstreamErrors.extractCode(e, objectMapper);
            String message = DownstreamErrors.describe(e, SERVICE_NAME);
            log.error(message);
            throw new WarehouseServiceException(OrderSagaState.SagaStep.WAREHOUSE_CONFIRM, code, message, e);
        } catch (ResourceAccessException e) {
            String message = SERVICE_NAME + " is unavailable: " + e.getMessage();
            log.error(message);
            throw new WarehouseServiceException(OrderSagaState.SagaStep.WAREHOUSE_CONFIRM, null, message, e);
        }
    }

    /**
     * Отмена (освобождение) резервов заказа (компенсация саги). Идемпотентна.
     * 404 (резервов для заказа нет) считается успешной компенсацией - нечего освобождать.
     */
    @CircuitBreaker(name = ResilienceConfig.WAREHOUSE, fallbackMethod = "cancelFallback")
    @Retry(name = ResilienceConfig.WAREHOUSE)
    @RateLimiter(name = ResilienceConfig.WAREHOUSE, fallbackMethod = "cancelFallback")
    public void cancel(Long orderId) {
        log.info("Cancelling product reservations via {}: orderId={}", SERVICE_NAME, orderId);
        try {
            warehouseRestClient.post()
                    .uri("/internal/products/reservations/{orderId}/cancel", orderId)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Product reservations cancelled via {} for orderId={}", SERVICE_NAME, orderId);
        } catch (HttpClientErrorException.NotFound e) {
            log.info("No product reservations found for orderId={} via {}, compensation is a no-op", orderId,
                    SERVICE_NAME);
        } catch (RestClientResponseException e) {
            String code = DownstreamErrors.extractCode(e, objectMapper);
            String message = DownstreamErrors.describe(e, SERVICE_NAME);
            log.error(message);
            throw new WarehouseServiceException(OrderSagaState.SagaStep.WAREHOUSE_CANCEL, code, message, e);
        } catch (ResourceAccessException e) {
            String message = SERVICE_NAME + " is unavailable: " + e.getMessage();
            log.error(message);
            throw new WarehouseServiceException(OrderSagaState.SagaStep.WAREHOUSE_CANCEL, null, message, e);
        }
    }

    /**
     * Получение текущего состояния резервов заказа.
     */
    @CircuitBreaker(name = ResilienceConfig.WAREHOUSE, fallbackMethod = "getReservationFallback")
    @Retry(name = ResilienceConfig.WAREHOUSE)
    @RateLimiter(name = ResilienceConfig.WAREHOUSE, fallbackMethod = "getReservationFallback")
    public ProductReservationListResponseDto getReservation(Long orderId) {
        log.info("Fetching product reservations via {}: orderId={}", SERVICE_NAME, orderId);
        try {
            return warehouseRestClient.get()
                    .uri("/internal/products/reservations/{orderId}", orderId)
                    .retrieve()
                    .body(ProductReservationListResponseDto.class);
        } catch (RestClientResponseException e) {
            String code = DownstreamErrors.extractCode(e, objectMapper);
            String message = DownstreamErrors.describe(e, SERVICE_NAME);
            log.error(message);
            throw new WarehouseServiceException(OrderSagaState.SagaStep.WAREHOUSE_RESERVE, code, message, e);
        } catch (ResourceAccessException e) {
            String message = SERVICE_NAME + " is unavailable: " + e.getMessage();
            log.error(message);
            throw new WarehouseServiceException(OrderSagaState.SagaStep.WAREHOUSE_RESERVE, null, message, e);
        }
    }

    /**
     * Fallback для {@link #reserve}: открытый circuit breaker / исчерпанный лимит
     * переводятся в машинные коды, прочие исключения пробрасываются как есть.
     */
    private void reserveFallback(Long orderId, Long productId, Integer quantity, String idempotencyKey,
                                 Throwable throwable) throws Throwable {
        throw resilienceFailure(SagaStep.WAREHOUSE_RESERVE, String.format("reserve for orderId=%d", orderId),
                throwable);
    }

    /**
     * Fallback для {@link #confirm}: см. {@link #reserveFallback}.
     */
    private void confirmFallback(Long orderId, Throwable throwable) throws Throwable {
        throw resilienceFailure(SagaStep.WAREHOUSE_CONFIRM, String.format("confirm for orderId=%d", orderId),
                throwable);
    }

    /**
     * Fallback для {@link #cancel}: см. {@link #reserveFallback}.
     */
    private void cancelFallback(Long orderId, Throwable throwable) throws Throwable {
        throw resilienceFailure(SagaStep.WAREHOUSE_CANCEL, String.format("cancel for orderId=%d", orderId),
                throwable);
    }

    /**
     * Fallback для {@link #getReservation}: см. {@link #reserveFallback}.
     */
    private void getReservationFallback(Long orderId, Throwable throwable) throws Throwable {
        throw resilienceFailure(SagaStep.WAREHOUSE_RESERVE, String.format("getReservation for orderId=%d", orderId),
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
            return new WarehouseServiceException(step, ErrorCodes.CIRCUIT_BREAKER_OPEN, message, throwable);
        }
        if (throwable instanceof RequestNotPermitted) {
            String message = String.format("%s rate limit exhausted, %s rejected: %s",
                    SERVICE_NAME, operation, throwable.getMessage());
            log.error(message);
            return new WarehouseServiceException(step, ErrorCodes.RATE_LIMITED, message, throwable);
        }
        return throwable;
    }

    private void validateReserved(Long orderId, ProductReservationListResponseDto response) {
        if (response == null || response.reservations() == null) {
            throw new WarehouseServiceException(OrderSagaState.SagaStep.WAREHOUSE_RESERVE, null,
                    SERVICE_NAME + " returned empty reservation response for orderId=" + orderId);
        }
        response.reservations().stream().map(ProductReservationResponseDto::reservationStatus).filter(
                status -> status != ReservationStatus.RESERVED && status != ReservationStatus.CONFIRMED)
                .forEach(status -> {
            throw new WarehouseServiceException(OrderSagaState.SagaStep.WAREHOUSE_RESERVE, null,
                    String.format("%s returned reservation in status %s for orderId=%d, expected RESERVED/CONFIRMED",
                            SERVICE_NAME, status, orderId));
        });
    }
}
