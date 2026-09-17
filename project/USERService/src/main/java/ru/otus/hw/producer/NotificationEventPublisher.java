package ru.otus.hw.producer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import ru.otus.hw.config.properties.RabbitMQProperties;
import ru.otus.hw.dto.NotificationEvent;
import ru.otus.hw.tracing.W3CTraceContextAdapter;

import java.time.Instant;
import java.util.UUID;

/**
 * Best-effort публикация уведомлений о жизненном цикле регистрации в канал
 * {@code notifications.events} / {@code notification.event} (USER → NOTIFICATION).
 *
 * <p>Уведомления не критичны: потеря при недоступном брокере допустима и фиксируется
 * ERROR-логом; основной путь (создание биллинг-аккаунта) идёт через transactional outbox.
 * Ошибки публикации никогда не ломают основной поток.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    private final RabbitMQProperties rabbitMQProperties;

    private final W3CTraceContextAdapter traceContextAdapter;

    /**
     * Публикует уведомление со случайным eventId: все источники USERService шлют одноразовые
     * уведомления; дедупликация повторных доставок - на стороне NOTIFICATION по eventId.
     */
    public void publish(Long userId, String status, String message) {
        NotificationEvent event = NotificationEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .orderId(null)
                .userId(userId)
                .price(null)
                .status(status)
                .message(message)
                .timestamp(Instant.now())
                .build();
        try {
            rabbitTemplate.convertAndSend(
                    rabbitMQProperties.getNotification().getExchangeName(),
                    rabbitMQProperties.getNotification().getRoutingKey(),
                    event,
                    messageToSend -> {
                        traceContextAdapter.injectCurrent(messageToSend,
                                (carrier, key, value) -> carrier.getMessageProperties().setHeader(key, value));
                        return messageToSend;
                    });
            log.info("Notification published: userId={}, status={}", userId, status);
        } catch (RuntimeException e) {
            log.error("Failed to publish notification (best-effort, swallowed): userId={}, status={}",
                    userId, status, e);
        }
    }
}
