package ru.otus.hw.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Момент "сообщение уходит в DLQ" виден из Java только в {@link MessageRecoverer}:
 * брокер наполняет DLQ сам (после {@link RejectAndDontRequeueRecoverer} +
 * {@code defaultRequeueRejected(false)} + DLX-аргументы очереди). Эта обёртка
 * инкрементирует счётчик {@code consumer.messages.to.dlq} с тегом {@code routing_key}
 * до делегирования обёрнутому рековереру.
 *
 * <p>Каунтеры известных очередей предрегистрируются со значением 0 в конструкторе:
 * лениво созданный каунтер (первое наблюдаемое значение сразу 1) невидим для
 * {@code increase()}/{@code rate()} - им нужны минимум два сэмпла, а переход 0→1
 * происходит между скрейпами.
 */
@Slf4j
public class DlqCountingRecoverer implements MessageRecoverer {

    private static final String METRIC_TO_DLQ = "consumer.messages.to.dlq";

    private static final String ROUTING_KEY_UNKNOWN = "unknown";

    private final MessageRecoverer delegate;

    private final MeterRegistry meterRegistry;

    private final Map<String, Counter> countersByRoutingKey = new ConcurrentHashMap<>();

    public DlqCountingRecoverer(@NonNull MeterRegistry meterRegistry, @NonNull String... knownRoutingKeys) {
        this(new RejectAndDontRequeueRecoverer(), meterRegistry, knownRoutingKeys);
    }

    public DlqCountingRecoverer(@NonNull MessageRecoverer delegate, @NonNull MeterRegistry meterRegistry,
                                @NonNull String @NonNull ... knownRoutingKeys) {
        this.delegate = delegate;
        this.meterRegistry = meterRegistry;
        for (String routingKey : knownRoutingKeys) {
            dlqCounter(routingKey);
        }
        dlqCounter(ROUTING_KEY_UNKNOWN);
    }

    @Override
    public void recover(@NonNull Message message, Throwable cause) {
        String routingKey = message.getMessageProperties().getReceivedRoutingKey();
        String tag = routingKey == null || routingKey.isBlank() ? ROUTING_KEY_UNKNOWN : routingKey;
        dlqCounter(tag).increment();
        log.error("Message exhausted retries and goes to DLQ: routingKey={}", tag, cause);
        delegate.recover(message, cause);
    }

    private Counter dlqCounter(@NonNull String routingKey) {
        return countersByRoutingKey.computeIfAbsent(routingKey, key -> Counter.builder(METRIC_TO_DLQ)
                .description("Messages rejected to DLQ after consumer retries exhausted")
                .tags(Tags.of("routing_key", key))
                .register(meterRegistry));
    }
}
