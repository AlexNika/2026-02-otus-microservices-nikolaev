package ru.otus.hw.config;

import io.github.resilience4j.common.circuitbreaker.configuration.CircuitBreakerConfigCustomizer;
import io.github.resilience4j.common.retry.configuration.RetryConfigCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.otus.hw.client.DownstreamFaults;
import ru.otus.hw.exception.BillingServiceException;
import ru.otus.hw.exception.ErrorCodes;
import ru.otus.hw.exception.SagaStepException;

import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Классификация ошибок для Resilience4j: задётся кодом (предикатами), а не списками
 * классов в yaml, потому что клиенты заворачивают все сбои в {@code BillingServiceException} /
 * {@link SagaStepException}, и наружу «сырые» IOException/HttpServerErrorException не выходят.
 *
 * <ul>
 *   <li>{@code CircuitBreaker.recordException = DownstreamFaults.isTransient}: в статистику
 *       отказов попадают только транзитные сбои (сеть/таймауты, 5хх, 409
 *       {@code CONCURRENT_MODIFICATION}). Бизнес-отказы 4хх, {@code CallNotPermittedException}
 *       и {@code RequestNotPermitted}/исключения с кодом {@code RATE_LIMITED} отказами НЕ
 *       считаются.</li>
 *   <li>{@code Retry.retryOnException}: та же классификация плюс исключения с кодом
 *       {@code RATE_LIMITED} (бэкофф ретраев = естественный троттлинг при исчерпании лимита).
 *       {@code CallNotPermittedException} дополнительно в {@code ignore-exceptions} (yaml) -
 *       попытки об открытый circuit breaker не жгутся.</li>
 * </ul>
 *
 * <p>Пороговые параметры (окна, тайминги) остаются в {@code application.yaml} и
 * переопределяются в тестах.
 */
@Configuration
public class ResilienceConfig {

    public static final String BILLING = "billingService";

    public static final String WAREHOUSE = "warehouseService";

    public static final String DELIVERY = "deliveryService";

    @Bean
    public CircuitBreakerConfigCustomizer billingCircuitBreakerCustomizer() {
        return CircuitBreakerConfigCustomizer.of(BILLING, recordTransientOnly());
    }

    @Bean
    public CircuitBreakerConfigCustomizer warehouseCircuitBreakerCustomizer() {
        return CircuitBreakerConfigCustomizer.of(WAREHOUSE, recordTransientOnly());
    }

    @Bean
    public CircuitBreakerConfigCustomizer deliveryCircuitBreakerCustomizer() {
        return CircuitBreakerConfigCustomizer.of(DELIVERY, recordTransientOnly());
    }

    @Bean
    public RetryConfigCustomizer billingRetryCustomizer() {
        return RetryConfigCustomizer.of(BILLING, retryTransientAndRateLimited());
    }

    @Bean
    public RetryConfigCustomizer warehouseRetryCustomizer() {
        return RetryConfigCustomizer.of(WAREHOUSE, retryTransientAndRateLimited());
    }

    @Bean
    public RetryConfigCustomizer deliveryRetryCustomizer() {
        return RetryConfigCustomizer.of(DELIVERY, retryTransientAndRateLimited());
    }

    private static Consumer<io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.Builder> recordTransientOnly() {
        return builder -> builder.recordException(DownstreamFaults::isTransient);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Consumer<io.github.resilience4j.retry.RetryConfig.Builder> retryTransientAndRateLimited() {
        Predicate<Throwable> retryPredicate = ResilienceConfig::retryable;
        return builder -> builder.retryOnException(retryPredicate);
    }

    /**
     * Ретраится транзитный сбой либо отказ из-за исчерпания лимита
     * {@code RateLimiter}'a (код {@link ErrorCodes#RATE_LIMITED}, проставленный
     * fallback'ом клиента для {@code RequestNotPermitted}).
     */
    private static boolean retryable(Throwable throwable) {
        return DownstreamFaults.isTransient(throwable) || hasRateLimitedCode(throwable);
    }

    private static boolean hasRateLimitedCode(Throwable throwable) {
        if (throwable instanceof SagaStepException sagaStepException) {
            return ErrorCodes.RATE_LIMITED.equals(sagaStepException.getCode());
        }
        if (throwable instanceof BillingServiceException billingServiceException) {
            return ErrorCodes.RATE_LIMITED.equals(billingServiceException.getCode());
        }
        return false;
    }
}
