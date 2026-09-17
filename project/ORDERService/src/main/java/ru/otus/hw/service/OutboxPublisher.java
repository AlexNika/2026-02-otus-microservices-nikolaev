package ru.otus.hw.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.otus.hw.config.properties.RabbitMQConfig;
import ru.otus.hw.dto.NotificationEvent;
import ru.otus.hw.models.OutboxEvent;
import ru.otus.hw.models.OutboxEvent.OutboxStatus;
import ru.otus.hw.repository.OutboxEventRepository;
import ru.otus.hw.tracing.W3CTraceContextAdapter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Scheduled-вычитка transactional outbox и публикация событий в RabbitMQ.
 *
 * <p>Успешно принятые брокером события помечаются SENT (nack/returned обрабатываются
 * confirm/return-колбэками RabbitTemplate - событие возвращается в NEW для повторной
 * публикации). Ошибка публикации увеличивает {@code attempts}; после исчерпания лимита
 * событие помечается FAILED и остаётся для ручного разбирательства. Повторные публикации
 * безопасны: consumer дедуплицирует по eventId (processed_messages).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    private static final int MAX_ATTEMPTS = 5;

    private static final String METRIC_PENDING = "outbox.events.pending";

    private static final String METRIC_PUBLISHED = "outbox.events.published";

    private static final String METRIC_FAILED = "outbox.events.failed";

    private static final String METRIC_REQUEUED = "outbox.events.requeued";

    private final OutboxEventRepository outboxEventRepository;

    private final RabbitTemplate rabbitTemplate;

    private final RabbitMQConfig rabbitMQConfig;

    private final ObjectMapper objectMapper;

    private final W3CTraceContextAdapter traceContextAdapter;

    private final MeterRegistry meterRegistry;

    /**
     * Текущее число событий в статусе NEW: gauge обновляется на каждом тике
     * {@link #publishPendingEvents()} (включая пустую вычитку - иначе значение застынет).
     */
    private final AtomicInteger pendingEventsGauge = new AtomicInteger(0);

    private Counter eventsPublishedCounter;

    private Counter eventsFailedCounter;

    private Counter eventsRequeuedCounter;

    /**
     * publisher-confirms-type: correlated (см. application.yaml): nack и returned-сообщения
     * возвращают событие outbox в NEW для повторной публикации. mandatory=true включает
     * возврат недоставленных (unroutable) сообщений вместо их молчаливого выбрасывания.
     */
    @PostConstruct
    void registerDeliveryCallbacks() {
        Gauge.builder(METRIC_PENDING, pendingEventsGauge, AtomicInteger::get)
                .description("Outbox events currently awaiting publication (status NEW)")
                .register(meterRegistry);
        eventsPublishedCounter = Counter.builder(METRIC_PUBLISHED)
                .description("Outbox events successfully published (status SENT)")
                .register(meterRegistry);
        eventsFailedCounter = Counter.builder(METRIC_FAILED)
                .description("Outbox events marked FAILED (attempts exhausted or corrupted payload)")
                .register(meterRegistry);
        eventsRequeuedCounter = Counter.builder(METRIC_REQUEUED)
                .description("Outbox events returned to NEW by broker nack/return")
                .register(meterRegistry);

        rabbitTemplate.setMandatory(true);
        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            if (!ack && correlationData != null && correlationData.getId() != null) {
                log.warn("Broker nack'd notification event eventId={}: {}", correlationData.getId(), cause);
                requeueByEventId(correlationData.getId(), "nack: " + cause);
            }
        });
        rabbitTemplate.setReturnsCallback(returned -> {
            String eventId = returned.getMessage().getMessageProperties().getCorrelationId();
            if (eventId != null) {
                log.warn("Notification event returned by broker: eventId={}, reply={}", eventId,
                        returned.getReplyText());
                requeueByEventId(eventId, "returned: " + returned.getReplyText());
            }
        });
    }

    @Scheduled(fixedDelayString = "${app.outbox.publish-delay:1000}")
    public void publishPendingEvents() {
        List<OutboxEvent> pendingEvents = outboxEventRepository.findByStatusOrderByCreatedAtAsc(OutboxStatus.NEW);
        pendingEventsGauge.set(pendingEvents.size());
        if (pendingEvents.isEmpty()) {
            return;
        }
        log.info("Outbox publisher: {} notification event(s) pending", pendingEvents.size());
        for (OutboxEvent event : pendingEvents) {
            publish(event);
        }
    }

    private void publish(@NonNull OutboxEvent event) {
        try {
            NotificationEvent notificationEvent = objectMapper.readValue(event.getPayload(),
                    NotificationEvent.class);

            CorrelationData correlationData = new CorrelationData(event.getEventId());
            rabbitTemplate.convertAndSend(
                    rabbitMQConfig.getExchangeName(),
                    rabbitMQConfig.getRoutingKey(),
                    notificationEvent,
                    message -> {
                        message.getMessageProperties().setCorrelationId(event.getEventId());
                        traceContextAdapter.inject(message,
                                (carrier, key, value) -> {
                                    assert carrier != null;
                                    carrier.getMessageProperties().setHeader(key, value);
                                },
                                event.getTraceparent(), event.getTracestate());
                        return message;
                    },
                    correlationData);

            event.setStatus(OutboxStatus.SENT);
            event.setSentAt(LocalDateTime.now());
            outboxEventRepository.save(event);
            eventsPublishedCounter.increment();
            log.info("Outbox event published: eventId={}, orderId={}", event.getEventId(),
                    notificationEvent.orderId());

        } catch (JsonProcessingException e) {
            log.error("Corrupted outbox payload, marking FAILED: eventId={}", event.getEventId());
            event.setStatus(OutboxStatus.FAILED);
            outboxEventRepository.save(event);
            eventsFailedCounter.increment();

        } catch (RuntimeException e) {
            int attempts = event.getAttempts() == null ? 0 : event.getAttempts();
            event.setAttempts(attempts + 1);
            if (event.getAttempts() >= MAX_ATTEMPTS) {
                event.setStatus(OutboxStatus.FAILED);
                eventsFailedCounter.increment();
                log.error("Outbox event publishing failed after {} attempts, marking FAILED: eventId={}",
                        event.getAttempts(), event.getEventId());
            } else {
                log.warn("Outbox event publishing failed (attempt {}), will retry: eventId={}",
                        event.getAttempts(), event.getEventId());
            }
            outboxEventRepository.save(event);
        }
    }

    /**
     * Возврат события в NEW по nack/returned от брокера (confirm/return-колбэки).
     */
    public void requeueByEventId(String eventId, String reason) {
        outboxEventRepository.findByEventId(eventId).ifPresent(event -> {
            if (event.getStatus() == OutboxStatus.NEW) {
                return;
            }
            event.setStatus(OutboxStatus.NEW);
            event.setSentAt(null);
            outboxEventRepository.save(event);
            eventsRequeuedCounter.increment();
            log.warn("Outbox event returned for republishing: eventId={}, reason={}", eventId, reason);
        });
    }
}
