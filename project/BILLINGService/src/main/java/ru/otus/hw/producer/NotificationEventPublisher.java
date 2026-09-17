package ru.otus.hw.producer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import ru.otus.hw.config.properties.RabbitMQProperties;
import ru.otus.hw.dto.NotificationEvent;
import ru.otus.hw.tracing.W3CTraceContextAdapter;

import java.time.Instant;

/**
 * Best-effort публикация уведомлений о жизненном цикле биллинг-аккаунта в канал
 * {@code notifications.events} / {@code notification.event} (BILLING → NOTIFICATION).
 *
 * <p>eventId задаёт вызывающий: для успеха - eventId входного UserCreatedEvent
 * (повторная доставка события не создаёт дубль уведомления), для сбоя - детерминированный
 * от userId (ретраи listener'а не плодят дубли). Дедупликация - в NOTIFICATIONService
 * по eventId (processed_messages). Ошибки публикации никогда не ломают основной поток.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    private final RabbitMQProperties rabbitMQProperties;

    private final W3CTraceContextAdapter traceContextAdapter;

    public void publish(@NonNull String eventId, Long userId, String status, String message) {
        NotificationEvent event = NotificationEvent.builder()
                .eventId(eventId)
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
            log.info("Notification published: userId={}, status={}, eventId={}", userId, status, eventId);
        } catch (RuntimeException e) {
            log.error("Failed to publish notification (best-effort, swallowed): userId={}, status={}",
                    userId, status, e);
        }
    }
}
