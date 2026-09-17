package ru.otus.hw.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import ru.otus.hw.client.DownstreamFaults;
import ru.otus.hw.exception.BillingServiceException;
import ru.otus.hw.exception.ErrorCodes;
import ru.otus.hw.exception.SagaStepException;
import ru.otus.hw.models.OrderSagaState.SagaStep;

import java.time.Duration;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Кастомные бизнес-метрики саги создания заказа (паттерн синхронной саги +
 * компенсации + recovery). В Prometheus имена транслируются как
 * {@code order_saga_*}; суффикс {@code _total} каунтерам добавляет сам
 * Micrometer, здесь имена задаются без него.
 *
 * <p>Кардинальность тегов ограничена: {@code step} и {@code outcome} -
 * фиксированные перечисления, {@code error_code} - машинные коды из
 * {@link ru.otus.hw.exception.ErrorCodes} + служебные {@code TRANSIENT}/{@code UNKNOWN}.
 */
@Slf4j
@Component
public class SagaMetrics {
    public static final String OUTCOME_PLACED = "placed";

    public static final String OUTCOME_COMPENSATED = "compensated";

    public static final String OUTCOME_COMPENSATION_FAILED = "compensation_failed";

    public static final String RECOVERY_KIND_STALE = "stale";

    public static final String RECOVERY_KIND_CONFIRMED_FINALIZATION = "confirmed_finalization";

    private static final String METRIC_STARTED = "order.saga.started";

    private static final String METRIC_STEP_ATTEMPTS = "order.saga.step.attempts";

    private static final String METRIC_STEP_DURATION = "order.saga.step.duration";

    private static final String METRIC_COMPENSATIONS = "order.saga.compensations";

    private static final String METRIC_COMPLETED = "order.saga.completed";

    private static final String METRIC_SAGA_DURATION = "order.saga.duration";

    private static final String METRIC_RECOVERY_TRIGGERED = "order.saga.recovery.triggered";

    private static final String METRIC_RECOVERY_FAILURES = "order.saga.recovery.failures";

    private static final String ERROR_CODE_NONE = "none";

    private static final String ERROR_CODE_TRANSIENT = "TRANSIENT";

    private static final String ERROR_CODE_UNKNOWN = "UNKNOWN";

    private static final SagaStep[] FORWARD_STEPS = {
            SagaStep.BILLING_WITHDRAW,
            SagaStep.WAREHOUSE_RESERVE,
            SagaStep.DELIVERY_RESERVE,
            SagaStep.WAREHOUSE_CONFIRM,
            SagaStep.DELIVERY_CONFIRM};

    private static final SagaStep[] COMPENSATION_STEPS = {
            SagaStep.DELIVERY_CANCEL,
            SagaStep.WAREHOUSE_CANCEL,
            SagaStep.BILLING_REFUND};

    private static final String[] OUTCOMES = {
            OUTCOME_PLACED, OUTCOME_COMPENSATED, OUTCOME_COMPENSATION_FAILED};

    /**
     * Полный известный набор значений тега {@code error_code}: машинные коды из
     * {@link ErrorCodes} + служебные {@code TRANSIENT}/{@code UNKNOWN}. По нему
     * каунтеры неудач предрегистрируются со значением 0.
     */
    private static final String[] KNOWN_ERROR_CODES = {
            ErrorCodes.INSUFFICIENT_STOCK,
            ErrorCodes.DELIVERY_NO_FREE_COURIER,
            ErrorCodes.DELIVERY_CAPACITY_CONFLICT,
            ErrorCodes.DELIVERY_RESERVATION_STATE_CONFLICT,
            ErrorCodes.DELIVERY_COURIER_ASSIGNMENT_FAILED,
            ErrorCodes.BILLING_INSUFFICIENT_FUNDS,
            ErrorCodes.BILLING_ACCOUNT_NOT_FOUND,
            ErrorCodes.BILLING_INVALID_AMOUNT,
            ErrorCodes.BILLING_ACCOUNT_INACTIVE,
            ErrorCodes.ORDER_STATE_CONFLICT,
            ErrorCodes.VALIDATION_FAILED,
            ErrorCodes.MALFORMED_REQUEST,
            ErrorCodes.DATA_INTEGRITY_VIOLATION,
            ErrorCodes.CONCURRENT_MODIFICATION,
            ErrorCodes.IDEMPOTENCY_KEY_CONFLICT,
            ErrorCodes.RESERVATION_ALREADY_CONFIRMED,
            ERROR_CODE_TRANSIENT,
            ERROR_CODE_UNKNOWN};

    private final Counter sagaStartedCounter;

    private final Counter recoveryFailuresCounter;

    private final Map<SagaStep, Counter> stepAttemptSuccessCounters = new EnumMap<>(SagaStep.class);

    private final Map<SagaStep, Timer> stepDurationTimers = new EnumMap<>(SagaStep.class);

    private final Map<SagaStep, Counter> compensationCounters = new EnumMap<>(SagaStep.class);

    private final Map<String, Counter> completedCounters = new HashMap<>();

    private final Map<String, Timer> sagaDurationTimers = new HashMap<>();

    private final Map<String, Counter> recoveryTriggeredCounters = new HashMap<>();

    /**
     * Динамические каунтеры неудачных попыток (тег {@code error_code} несёт машинный код
     * отказа). Ключ - "шаг|код"; кардинальность ограничена набором кодов ошибки.
     */
    private final Map<String, Counter> stepAttemptFailureCounters = new ConcurrentHashMap<>();

    private final MeterRegistry meterRegistry;

    public SagaMetrics(@NonNull MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.sagaStartedCounter = Counter.builder(METRIC_STARTED)
                .description("Number of started order sagas")
                .register(meterRegistry);
        this.recoveryFailuresCounter = Counter.builder(METRIC_RECOVERY_FAILURES)
                .description("Number of failed saga recovery runs (per saga)")
                .register(meterRegistry);

        for (SagaStep step : FORWARD_STEPS) {
            stepAttemptSuccessCounters.put(step, Counter.builder(METRIC_STEP_ATTEMPTS)
                    .description("Order saga step attempts with result")
                    .tags(Tags.of("step", stepName(step), "result", "success", "error_code", ERROR_CODE_NONE))
                    .register(meterRegistry));
            stepDurationTimers.put(step, Timer.builder(METRIC_STEP_DURATION)
                    .description("Order saga step duration including retries")
                    .tag("step", stepName(step))
                    .publishPercentileHistogram()
                    .publishPercentiles(0.5, 0.95, 0.99)
                    .register(meterRegistry));
            for (String errorCode : KNOWN_ERROR_CODES) {
                stepAttemptFailureCounters.put(step.name() + "|" + errorCode,
                        registerFailureCounter(step, errorCode));
            }
        }
        for (SagaStep step : COMPENSATION_STEPS) {
            compensationCounters.put(step, Counter.builder(METRIC_COMPENSATIONS)
                    .description("Executed saga compensation actions")
                    .tag("step", stepName(step))
                    .register(meterRegistry));
        }
        for (String outcome : OUTCOMES) {
            completedCounters.put(outcome, Counter.builder(METRIC_COMPLETED)
                    .description("Sagas reaching terminal outcome")
                    .tag("outcome", outcome)
                    .register(meterRegistry));
            sagaDurationTimers.put(outcome, Timer.builder(METRIC_SAGA_DURATION)
                    .description("Saga end-to-end duration by terminal outcome")
                    .tag("outcome", outcome)
                    .publishPercentileHistogram()
                    .publishPercentiles(0.5, 0.95, 0.99)
                    .register(meterRegistry));
        }
        for (String kind : new String[]{RECOVERY_KIND_STALE, RECOVERY_KIND_CONFIRMED_FINALIZATION}) {
            recoveryTriggeredCounters.put(kind, Counter.builder(METRIC_RECOVERY_TRIGGERED)
                    .description("Sagas picked up by recovery")
                    .tag("kind", kind)
                    .register(meterRegistry));
        }
    }

    /**
     * Точка старта саги: возвращает сэмпл для замера полной длительности,
     * завершаемый в {@link #sagaCompleted(String, Timer.Sample)}.
     */
    public Timer.Sample startSaga() {
        sagaStartedCounter.increment();
        return Timer.start(meterRegistry);
    }

    public void stepAttemptSuccess(@NonNull SagaStep step) {
        stepAttemptSuccessCounters.get(step).increment();
    }

    public void stepAttemptFailure(@NonNull SagaStep step, @NonNull Exception failure) {
        String errorCode = errorCodeOf(failure);
        stepAttemptFailureCounters
                .computeIfAbsent(step.name() + "|" + errorCode, key -> registerFailureCounter(step, errorCode))
                .increment();
    }

    /**
     * Длительность шага саги вместе с ретраями: старт - до вызова шага
     * (ретраи - на стороне клиента через Resilience4j), стоп - после завершения или отказа.
     */
    public Timer.Sample startStep() {
        return Timer.start(meterRegistry);
    }

    public void stopStep(Timer.@NonNull Sample sample, @NonNull SagaStep step) {
        sample.stop(stepDurationTimers.get(step));
    }

    public void compensation(@NonNull SagaStep step) {
        compensationCounters.get(step).increment();
    }

    /**
     * Терминал синхронного пути саги: счётчик по исходу + полная длительность
     * от входа {@code runSaga}.
     */
    public void sagaCompleted(@NonNull String outcome, Timer.@NonNull Sample sample) {
        completedCounters.get(outcome).increment();
        sample.stop(sagaDurationTimers.get(outcome));
    }

    /**
     * Терминал саги через recovery-путь: полная длительность считается от
     * создания записи саги, поэтому включает простой упавшего пода.
     * При {@code duration == null} фиксируется только исход, без длительности.
     */
    public void sagaCompleted(@NonNull String outcome, @Nullable Duration duration) {
        completedCounters.get(outcome).increment();
        if (duration != null) {
            sagaDurationTimers.get(outcome).record(duration);
        }
    }

    public void recoveryTriggered(@NonNull String kind) {
        recoveryTriggeredCounters.get(kind).increment();
    }

    public void recoveryFailure() {
        recoveryFailuresCounter.increment();
    }

    private Counter registerFailureCounter(@NonNull SagaStep step, @NonNull String errorCode) {
        return Counter.builder(METRIC_STEP_ATTEMPTS)
                .description("Order saga step attempts with result")
                .tags(Tags.of("step", stepName(step), "result", "failed", "error_code", errorCode))
                .register(meterRegistry);
    }

    /**
     * Машинный код отказа шага для тега {@code error_code}: коды из
     * {@link ru.otus.hw.exception.ErrorCodes} у {@link SagaStepException}/
     * {@link BillingServiceException}; {@code TRANSIENT} для транзитных ошибок
     * (таймауты/сеть, 5xx, конфликты версий); {@code UNKNOWN} для остальных.
     */
    private static String errorCodeOf(@NonNull Exception failure) {
        if (failure instanceof SagaStepException sagaStepException
                && sagaStepException.getCode() != null) {
            return sagaStepException.getCode();
        }
        if (failure instanceof BillingServiceException billingException
                && billingException.getCode() != null) {
            return billingException.getCode();
        }
        return DownstreamFaults.isTransient(failure) ? ERROR_CODE_TRANSIENT : ERROR_CODE_UNKNOWN;
    }

    private static String stepName(@NonNull SagaStep step) {
        return step.name().toLowerCase();
    }
}
