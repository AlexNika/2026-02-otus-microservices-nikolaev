package ru.otus.hw.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import ru.otus.hw.exception.BillingServiceException;
import ru.otus.hw.exception.ErrorCodes;
import ru.otus.hw.exception.WarehouseServiceException;
import ru.otus.hw.models.OrderSagaState.SagaStep;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проверка имён/тегов кастомных метрик саги в Prometheus-формате
 * (суффикс _total каунтерам добавляет Micrometer).
 */
class SagaMetricsTest {

    private SimpleMeterRegistry registry;

    private SagaMetrics sagaMetrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        sagaMetrics = new SagaMetrics(registry);
    }

    private double counter(String name, String tags) {
        Counter counter = tags.isEmpty()
                ? registry.find(name).counter()
                : registry.find(name).tags(splitTags(tags)).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private static String[] splitTags(String tags) {
        return tags.split(",");
    }

    @Test
    @DisplayName("startSaga инкрементит started и возвращает сэмпл; терминал фиксирует outcome и длительность")
    void shouldCountStartedAndCompletedOnSuccess() {
        Timer.Sample sample = sagaMetrics.startSaga();

        assertThat(counter("order.saga.started", "")).isEqualTo(1.0);

        sagaMetrics.sagaCompleted(SagaMetrics.OUTCOME_PLACED, sample);

        assertThat(counter("order.saga.completed", "outcome,placed")).isEqualTo(1.0);
        Timer durationTimer = registry.find("order.saga.duration").tags("outcome", "placed").timer();
        assertThat(durationTimer).isNotNull();
        assertThat(durationTimer.count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("успешный шаг: attempts{result=success,error_code=none} + длительность")
    void shouldCountSuccessfulStepAttempt() {
        Timer.Sample stepSample = sagaMetrics.startStep();
        sagaMetrics.stepAttemptSuccess(SagaStep.BILLING_WITHDRAW);
        sagaMetrics.stopStep(stepSample, SagaStep.BILLING_WITHDRAW);

        assertThat(counter("order.saga.step.attempts",
                "step,billing_withdraw,result,success,error_code,none")).isEqualTo(1.0);
        Timer stepTimer = registry.find("order.saga.step.duration").tags("step", "billing_withdraw").timer();
        assertThat(stepTimer).isNotNull();
        assertThat(stepTimer.count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("бизнес-отказ с машинным кодом: error_code из SagaStepException")
    void shouldUseErrorCodeFromSagaStepException() {
        sagaMetrics.stepAttemptFailure(SagaStep.WAREHOUSE_RESERVE,
                new WarehouseServiceException(SagaStep.WAREHOUSE_RESERVE, ErrorCodes.INSUFFICIENT_STOCK, "no stock"));

        assertThat(counter("order.saga.step.attempts",
                "step,warehouse_reserve,result,failed,error_code,INSUFFICIENT_STOCK")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("код биллинга: error_code из BillingServiceException")
    void shouldUseErrorCodeFromBillingServiceException() {
        sagaMetrics.stepAttemptFailure(SagaStep.BILLING_WITHDRAW,
                new BillingServiceException("no funds", null, false, ErrorCodes.BILLING_INSUFFICIENT_FUNDS, 400));

        assertThat(counter("order.saga.step.attempts",
                "step,billing_withdraw,result,failed,error_code,BILLING_INSUFFICIENT_FUNDS")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("транзитная ошибка (сеть): error_code=TRANSIENT")
    void shouldUseTransientCodeForTransientFailures() {
        sagaMetrics.stepAttemptFailure(SagaStep.DELIVERY_RESERVE,
                new ResourceAccessException("connection timed out"));

        assertThat(counter("order.saga.step.attempts",
                "step,delivery_reserve,result,failed,error_code,TRANSIENT")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("прочая ошибка: error_code=UNKNOWN")
    void shouldUseUnknownCodeForOtherFailures() {
        sagaMetrics.stepAttemptFailure(SagaStep.DELIVERY_CONFIRM, new IllegalStateException("boom"));

        assertThat(counter("order.saga.step.attempts",
                "step,delivery_confirm,result,failed,error_code,UNKNOWN")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("компенсации считаются по шагам")
    void shouldCountCompensationsByStep() {
        sagaMetrics.compensation(SagaStep.DELIVERY_CANCEL);
        sagaMetrics.compensation(SagaStep.WAREHOUSE_CANCEL);
        sagaMetrics.compensation(SagaStep.BILLING_REFUND);
        sagaMetrics.compensation(SagaStep.BILLING_REFUND);

        assertThat(counter("order.saga.compensations", "step,delivery_cancel")).isEqualTo(1.0);
        assertThat(counter("order.saga.compensations", "step,warehouse_cancel")).isEqualTo(1.0);
        assertThat(counter("order.saga.compensations", "step,billing_refund")).isEqualTo(2.0);
    }

    @Test
    @DisplayName("терминалы компенсации и compensation_failed считаются отдельно")
    void shouldCountCompensationOutcomes() {
        sagaMetrics.sagaCompleted(SagaMetrics.OUTCOME_COMPENSATED, Duration.ofSeconds(2));
        sagaMetrics.sagaCompleted(SagaMetrics.OUTCOME_COMPENSATION_FAILED, (Duration) null);

        assertThat(counter("order.saga.completed", "outcome,compensated")).isEqualTo(1.0);
        assertThat(counter("order.saga.completed", "outcome,compensation_failed")).isEqualTo(1.0);
        Timer compensatedTimer = registry.find("order.saga.duration").tags("outcome", "compensated").timer();
        assertThat(compensatedTimer.count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("recovery: triggered по виду и счётчик сбоев")
    void shouldCountRecoveryTriggersAndFailures() {
        sagaMetrics.recoveryTriggered(SagaMetrics.RECOVERY_KIND_STALE);
        sagaMetrics.recoveryTriggered(SagaMetrics.RECOVERY_KIND_CONFIRMED_FINALIZATION);
        sagaMetrics.recoveryFailure();

        assertThat(counter("order.saga.recovery.triggered", "kind,stale")).isEqualTo(1.0);
        assertThat(counter("order.saga.recovery.triggered", "kind,confirmed_finalization")).isEqualTo(1.0);
        assertThat(counter("order.saga.recovery.failures", "")).isEqualTo(1.0);
    }
}
