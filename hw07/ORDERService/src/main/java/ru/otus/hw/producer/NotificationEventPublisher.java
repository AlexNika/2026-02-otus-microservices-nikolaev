package ru.otus.hw.producer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import ru.otus.hw.config.properties.RabbitMQConfig;
import ru.otus.hw.dto.NotificationEvent;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    private final RabbitMQConfig rabbitMQConfig;

    public void send(NotificationEvent event) {
        try {
            rabbitTemplate.convertAndSend(
                    rabbitMQConfig.getExchangeName(),
                    rabbitMQConfig.getRoutingKey(),
                    event
            );
            log.info("Notification event published: orderId={}, userId={}, status={}",
                    event.orderId(), event.userId(), event.status());
        } catch (AmqpException e) {
            log.error("Failed to publish notification event: orderId={}, userId={}, status={}",
                    event.orderId(), event.userId(), event.status(), e);
        }
    }
}
