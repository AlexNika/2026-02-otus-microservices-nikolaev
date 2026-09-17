package ru.otus.hw.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Метрики приёма/обработки событий консьюмерами (каналы отправки в проекте отсутствуют -
 * события только принимаются и сохраняются).
 *
 * <p>{@code notification.events.received} - вход {@code NotificationConsumer}, тег
 * {@code event_type} = статус события (ограниченный набор: PLACED, FAILED,
 * ACCOUNT_CREATED, ACCOUNT_CREATION_FAILED).
 *
 * <p>{@code notification.events.processed} - результат обработки консьюмером с тегами
 * {@code consumer=notification|user_sync} и {@code result=applied|duplicate|failed}
 * (duplicate - дедупликация по eventId и пропуск устаревших событий).
 */
@Slf4j
@Component
public class ConsumerMetrics {

    public static final String CONSUMER_NOTIFICATION = "notification";

    public static final String CONSUMER_USER_SYNC = "user_sync";

    public static final String RESULT_APPLIED = "applied";

    public static final String RESULT_DUPLICATE = "duplicate";

    public static final String RESULT_FAILED = "failed";

    private static final String METRIC_RECEIVED = "notification.events.received";

    private static final String METRIC_PROCESSED = "notification.events.processed";

    private static final List<String> KNOWN_EVENT_TYPES =
            List.of("PLACED", "FAILED", "ACCOUNT_CREATED", "ACCOUNT_CREATION_FAILED");

    private static final List<String> CONSUMERS = List.of(CONSUMER_NOTIFICATION, CONSUMER_USER_SYNC);

    private static final List<String> RESULTS = List.of(RESULT_APPLIED, RESULT_DUPLICATE, RESULT_FAILED);

    private final MeterRegistry meterRegistry;

    public ConsumerMetrics(@NonNull MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        for (String eventType : KNOWN_EVENT_TYPES) {
            receivedCounter(eventType);
        }
        for (String consumer : CONSUMERS) {
            for (String result : RESULTS) {
                processedCounter(consumer, result);
            }
        }
    }

    public void notificationReceived(String eventType) {
        String type = eventType == null || eventType.isBlank() ? "UNKNOWN" : eventType;
        receivedCounter(type).increment();
    }

    public void processed(@NonNull String consumer, @NonNull String result) {
        processedCounter(consumer, result).increment();
    }

    private io.micrometer.core.instrument.Counter receivedCounter(@NonNull String eventType) {
        return meterRegistry.counter(METRIC_RECEIVED, Tags.of("event_type", eventType));
    }

    private io.micrometer.core.instrument.Counter processedCounter(@NonNull String consumer,
                                                                   @NonNull String result) {
        return meterRegistry.counter(METRIC_PROCESSED, Tags.of("consumer", consumer, "result", result));
    }
}
