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
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import ru.otus.hw.config.properties.RabbitMQProperties;
import ru.otus.hw.dto.UserCreatedEvent;
import ru.otus.hw.models.OutboxEvent;
import ru.otus.hw.models.OutboxEvent.OutboxStatus;
import ru.otus.hw.repository.OutboxEventRepository;
import ru.otus.hw.tracing.W3CTraceContextAdapter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Scheduled-вычитка auth_outbox и публикация расширенного UserCreatedEvent в RabbitMQ
 * (users.events / user.created). Копия паттерна USERService: publisher-confirms,
 * MAX_ATTEMPTS=5 → FAILED.
 *
 * <p>SENT выставляется ТОЛЬКО по confirm(ack) брокера: отправка без подтверждения
 * оставляет событие NEW, поэтому потеря confirm/return (брокер недоступен, очередь ещё
 * не объявлена потребителем, NO_ROUTE) не приводит к потере события - следующий тик
 * публикует его повторно (потребители идемпотентны по natural key: USER - PK users.id,
 * BILLING - accounts.user_id).
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

    private final RabbitMQProperties rabbitMQProperties;

    private final ObjectMapper objectMapper;

    private final W3CTraceContextAdapter traceContextAdapter;

    private final MeterRegistry meterRegistry;

    /**
     * eventId, по которым брокер вернул сообщение (unroutable): такой confirm(ack)
     * не должен помечать событие SENT, оно остаётся NEW для повторной публикации.
     */
    private final Set<String> returnedEventIds = ConcurrentHashMap.newKeySet();

    /**
     * Текущее число событий в статусе NEW: gauge обновляется на каждом тике
     * {@link #publishPendingEvents()} (включая пустую вычитку - иначе значение застынет).
     */
    private final AtomicInteger pendingEventsGauge = new AtomicInteger(0);

    private Counter eventsPublishedCounter;

    private Counter eventsFailedCounter;

    private Counter eventsRequeuedCounter;

    @PostConstruct
    void registerDeliveryCallbacks() {
        registerOutboxMetrics();
        configureRabbitTemplateCallbacks();
    }

    private void registerOutboxMetrics() {
        Gauge.builder(METRIC_PENDING, pendingEventsGauge, AtomicInteger::get)
                .description("Outbox events currently awaiting publication (status NEW)")
                .register(meterRegistry);

        eventsPublishedCounter = counter(METRIC_PUBLISHED,
                "Outbox events confirmed by broker and marked SENT");

        eventsFailedCounter = counter(METRIC_FAILED,
                "Outbox events marked FAILED (attempts exhausted or corrupted payload)");

        eventsRequeuedCounter = counter(METRIC_REQUEUED,
                "Outbox events returned to NEW after being marked SENT (broker return)");
    }

    /**
     * publisher-confirm-type: correlated (см. application.yaml).<br>
     * mandatory=true включает возврат не доставленных (unroutable) сообщений вместо их молчаливого выбрасывания.
     */
    private void configureRabbitTemplateCallbacks() {
        rabbitTemplate.setMandatory(true);
        rabbitTemplate.setConfirmCallback(this::handlePublisherConfirm);
        rabbitTemplate.setReturnsCallback(this::handleReturnedMessage);
    }

    private @NonNull Counter counter(String name, String description) {
        return Counter.builder(name)
                .description(description)
                .register(meterRegistry);
    }

    private void handlePublisherConfirm(CorrelationData correlationData, boolean ack, String cause) {
        String eventId = correlationData == null ? null : correlationData.getId();

        if (!StringUtils.hasText(eventId)) {
            return;
        }

        if (!ack) {
            log.warn("Broker nack'd outbox event eventId={}, keeping NEW for republish: {}", eventId, cause);
            return;
        }

        if (returnedEventIds.remove(eventId)) {
            log.warn("Broker returned outbox event after confirm, keeping NEW for republish: eventId={}", eventId);
            return;
        }

        markSentByEventId(eventId);
    }

    private void handleReturnedMessage(@NonNull ReturnedMessage returned) {
        String eventId = returned.getMessage().getMessageProperties().getCorrelationId();

        if (eventId == null) {
            return;
        }

        log.warn("Outbox event returned by broker (unroutable), keeping NEW for republish: eventId={}, reply={}",
                eventId, returned.getReplyText());

        returnedEventIds.add(eventId);
        requeueIfAlreadySent(eventId);
    }

    @Scheduled(fixedDelayString = "${app.outbox.publish-delay:1000}")
    public void publishPendingEvents() {
        List<OutboxEvent> pendingEvents = outboxEventRepository.findByStatusOrderByCreatedAsc(OutboxStatus.NEW);
        pendingEventsGauge.set(pendingEvents.size());
        if (pendingEvents.isEmpty()) {
            return;
        }
        log.info("Outbox publisher: {} event(s) pending", pendingEvents.size());
        for (OutboxEvent event : pendingEvents) {
            publish(event);
        }
    }

    private void publish(@NonNull OutboxEvent event) {
        try {
            UserCreatedEvent userCreatedEvent = objectMapper.readValue(event.getPayload(),
                    UserCreatedEvent.class);
            send(event,
                    rabbitMQProperties.getProducer().getExchangeName(),
                    rabbitMQProperties.getProducer().getRoutingKey(),
                    userCreatedEvent);
        } catch (JsonProcessingException e) {
            log.error("Corrupted outbox payload, marking FAILED: eventId={}", event.getEventId(), e);
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
                        event.getAttempts(), event.getEventId(), e);
            } else {
                log.warn("Outbox event publishing failed (attempt {}), will retry: eventId={}",
                        event.getAttempts(), event.getEventId(), e);
            }
            outboxEventRepository.save(event);
        }
    }

    /**
     * Отправка без немедленной отметки SENT: статус изменит confirm-колбэк
     * (ack → SENT, nack/returned/нет подтверждения → остаётся NEW для повторной публикации).
     */
    private void send(@NonNull OutboxEvent event, String exchange, String routingKey, @NonNull Object payload) {
        CorrelationData correlationData = new CorrelationData(event.getEventId());
        rabbitTemplate.convertAndSend(
                exchange,
                routingKey,
                payload,
                message -> {
                    message.getMessageProperties().setCorrelationId(event.getEventId());
                    traceContextAdapter.inject(message, (carrier, key, value) -> {
                                assert carrier != null;
                                carrier.getMessageProperties().setHeader(key, value);
                            },
                            event.getTraceparent(), event.getTracestate());
                    return message;
                },
                correlationData);
        log.info("Outbox event sent to broker (awaiting confirm): eventId={}, exchange={}, routingKey={}",
                event.getEventId(), exchange, routingKey);
    }

    /**
     * Отметка SENT по confirm(ack) брокера.
     */
    void markSentByEventId(@NonNull String eventId) {
        outboxEventRepository.findByEventId(eventId).ifPresent(event -> {
            if (event.getStatus() != OutboxStatus.NEW) {
                return;
            }
            event.setStatus(OutboxStatus.SENT);
            event.setSentAt(LocalDateTime.now());
            outboxEventRepository.save(event);
            eventsPublishedCounter.increment();
            log.info("Outbox event confirmed by broker, marked SENT: eventId={}", eventId);
        });
    }

    /**
     * Returned может прийти после confirm(ack), когда событие уже помечено SENT:
     * возвращаем его в NEW для повторной публикации.
     */
    void requeueIfAlreadySent(@NonNull String eventId) {
        outboxEventRepository.findByEventId(eventId).ifPresent(event -> {
            if (event.getStatus() != OutboxStatus.SENT) {
                return;
            }
            event.setStatus(OutboxStatus.NEW);
            event.setSentAt(null);
            outboxEventRepository.save(event);
            eventsRequeuedCounter.increment();
            log.warn("Outbox event returned after being marked SENT, back to NEW: eventId={}", eventId);
        });
    }
}
