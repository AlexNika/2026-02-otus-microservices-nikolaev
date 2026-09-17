package ru.otus.hw.client;

import org.jspecify.annotations.NonNull;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import ru.otus.hw.exception.BillingServiceException;
import ru.otus.hw.exception.ErrorCodes;
import ru.otus.hw.exception.SagaStepException;

/**
 * Единая классификация отказов downstream-сервисов (BILLING/WAREHOUSE/DELIVERY) для
 * Resilience4j (CircuitBreaker {@code recordException} / Retry {@code retryOnException}).
 *
 * <p>Транзитные (учитываются circuit breaker'ом как отказы и ретраятся):
 * сеть/таймауты ({@link ResourceAccessException}), 5xx downstream,
 * 409 {@link ErrorCodes#CONCURRENT_MODIFICATION}.
 *
 * <p>Бизнес-отказы (4хх: {@code BILLING_INSUFFICIENT_FUNDS}, {@code INSUFFICIENT_STOCK},
 * {@code DELIVERY_NO_FREE_COURIER} и т.п.) НЕ транзитные: они не записываются в
 * circuit breaker (иначе пара заказов с нулевым балансом открыла бы circuit на весь
 * сервис) и не ретраятся - шаг саги сразу ведёт к компенсации.
 *
 * <p>{@code CallNotPermittedException} / {@code RequestNotPermitted} сюда не попадают:
 * это решения самих ограничителей, а не отказы downstream.
 */
public final class DownstreamFaults {

    private DownstreamFaults() {
    }

    /**
     * Транзитный сбой: вызов можно безопасно повторить (идемпотентность шагов обеспечена
     * downstream-сервисами).
     */
    public static boolean isTransient(@NonNull Throwable e) {
        switch (e) {
            case ResourceAccessException _ -> {
                return true;
            }
            case BillingServiceException billingException -> {
                return billingException.isTransientError();
            }
            case SagaStepException sagaStepException -> {
                if (ErrorCodes.CONCURRENT_MODIFICATION.equals(sagaStepException.getCode())) {
                    return true;
                }
                Throwable cause = sagaStepException.getCause();
                if (cause instanceof ResourceAccessException) {
                    return true;
                }
                return cause instanceof RestClientResponseException responseException
                        && responseException.getStatusCode().is5xxServerError();
            }
            default -> {
            }
        }
        return false;
    }
}
