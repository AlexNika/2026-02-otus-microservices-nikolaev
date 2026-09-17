package ru.otus.hw.service;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import ru.otus.hw.exception.BillingServiceException;
import ru.otus.hw.exception.ErrorCodes;
import ru.otus.hw.exception.SagaStepException;

/**
 * Ретраи шагов саги на транзитные ошибки: до
 * {@value #MAX_ATTEMPTS} попыток с экспоненциальным backoff (200мс × 2).
 *
 * <p>Классификация ошибок:
 * <ul>
 *   <li>транзитные (ретраятся): таймауты/сеть ({@link ResourceAccessException}),
 *       5xx downstream, 409 {@link ErrorCodes#CONCURRENT_MODIFICATION};</li>
 *   <li>бизнес-отказы и прочие 4xx (сразу компенсация, 0 ретраев):
 *       INSUFFICIENT_STOCK, BILLING_INSUFFICIENT_FUNDS, DELIVERY_NO_FREE_COURIER и т.п.</li>
 * </ul>
 */
@Slf4j
public final class SagaStepRetrier {

    private static final int MAX_ATTEMPTS = 3;

    private static final long INITIAL_BACKOFF_MS = 200;

    private SagaStepRetrier() {
    }

    /**
     * Выполняет шаг саги с ретраями на транзитные ошибки. Нетранзитная ошибка пробрасывается
     * сразу (без повторов) — вызывающий код запускает компенсацию.
     */
    public static void executeWithRetry(@NonNull String stepName, @NonNull Runnable step) {
        long backoff = INITIAL_BACKOFF_MS;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                step.run();
                return;
            } catch (Exception e) {
                if (!isTransient(e) || attempt == MAX_ATTEMPTS) {
                    if (attempt > 1) {
                        log.error("Saga step {} failed after {} attempts", stepName, attempt, e);
                    }
                    throw asRuntime(e);
                }
                log.warn("Transient error on saga step {} (attempt {} of {}), retrying in {} ms",
                        stepName, attempt, MAX_ATTEMPTS, backoff, e);
                sleep(backoff);
                backoff *= 2;
            }
        }
    }

    /**
     * Транзитная ошибка - шаг можно безопасно повторить (идемпотентность шагов обеспечена
     * downstream-сервисами).
     */
    public static boolean isTransient(@NonNull Exception e) {
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

    private static RuntimeException asRuntime(@NonNull Exception e) {
        if (e instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new IllegalStateException("Unexpected checked exception during saga step", e);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Saga step retry interrupted", e);
        }
    }
}
