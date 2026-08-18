package ru.otus.hw.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.otus.hw.config.properties.RabbitMQProperties;
import ru.otus.hw.dto.UserCreatedEvent;
import ru.otus.hw.models.OutboxEvent;
import ru.otus.hw.models.OutboxEvent.OutboxStatus;
import ru.otus.hw.repository.OutboxEventRepository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Scheduled-вычитка user_outbox и публикация UserCreatedEvent в RabbitMQ
 * (exchange users.events, routing key user.created).
 *
 * <p>Успешно принятые брокером события помечаются SENT (nack/returned обрабатываются
 * confirm/return-колбэками RabbitTemplate — событие возвращается в NEW для повторной
 * публикации). Ошибка публикации увеличивает {@code attempts}; после исчерпания лимита
 * событие помечается FAILED и остаётся для ручного разбирательства. Повторные публикации
 * безопасны: BILLING идемпотентен по natural key userId.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    private static final int MAX_ATTEMPTS = 5;

    private final OutboxEventRepository outboxEventRepository;

    private final RabbitTemplate rabbitTemplate;

    private final RabbitMQProperties rabbitMQProperties;

    private final ObjectMapper objectMapper;

    /**
     * publisher-confirm-type: correlated (см. application.yaml): nack и returned-сообщения
     * возвращают событие outbox в NEW для повторной публикации. mandatory=true включает
     * возврат недоставленных (unroutable) сообщений вместо их молчаливого выбрасывания.
     */
    @PostConstruct
    void registerDeliveryCallbacks() {
        rabbitTemplate.setMandatory(true);
        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            if (!ack && correlationData != null && correlationData.getId() != null) {
                log.warn("Broker nack'd user-created event eventId={}: {}", correlationData.getId(), cause);
                requeueByEventId(correlationData.getId(), "nack: " + cause);
            }
        });
        rabbitTemplate.setReturnsCallback(returned -> {
            String eventId = returned.getMessage().getMessageProperties().getCorrelationId();
            if (eventId != null) {
                log.warn("User-created event returned by broker: eventId={}, reply={}", eventId,
                        returned.getReplyText());
                requeueByEventId(eventId, "returned: " + returned.getReplyText());
            }
        });
    }

    @Scheduled(fixedDelayString = "${app.outbox.publish-delay:1000}")
    public void publishPendingEvents() {
        List<OutboxEvent> pendingEvents = outboxEventRepository.findByStatusOrderByCreatedAtAsc(OutboxStatus.NEW);
        if (pendingEvents.isEmpty()) {
            return;
        }
        log.info("Outbox publisher: {} user-created event(s) pending", pendingEvents.size());
        for (OutboxEvent event : pendingEvents) {
            publish(event);
        }
    }

    private void publish(@NonNull OutboxEvent event) {
        try {
            UserCreatedEvent userCreatedEvent = objectMapper.readValue(event.getPayload(),
                    UserCreatedEvent.class);

            CorrelationData correlationData = new CorrelationData(event.getEventId());
            rabbitTemplate.convertAndSend(
                    rabbitMQProperties.getProducer().getExchangeName(),
                    rabbitMQProperties.getProducer().getRoutingKey(),
                    userCreatedEvent,
                    message -> {
                        message.getMessageProperties().setCorrelationId(event.getEventId());
                        return message;
                    },
                    correlationData);

            event.setStatus(OutboxStatus.SENT);
            event.setSentAt(LocalDateTime.now());
            outboxEventRepository.save(event);
            log.info("Outbox event published: eventId={}, userId={}", event.getEventId(),
                    userCreatedEvent.userId());

        } catch (JsonProcessingException e) {
            log.error("Corrupted outbox payload, marking FAILED: eventId={}", event.getEventId(), e);
            event.setStatus(OutboxStatus.FAILED);
            outboxEventRepository.save(event);

        } catch (RuntimeException e) {
            int attempts = event.getAttempts() == null ? 0 : event.getAttempts();
            event.setAttempts(attempts + 1);
            if (event.getAttempts() >= MAX_ATTEMPTS) {
                event.setStatus(OutboxStatus.FAILED);
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
            log.warn("Outbox event returned for republishing: eventId={}, reason={}", eventId, reason);
        });
    }
}
