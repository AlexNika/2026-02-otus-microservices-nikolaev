package ru.otus.hw.consumer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import ru.otus.hw.dto.NotificationEvent;
import ru.otus.hw.service.NotificationService;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationConsumer {

    private final NotificationService notificationService;

    @RabbitListener(queues = "${app.rabbitmq.queue-name:notification.queue}")
    public void handleNotification(NotificationEvent event) {
        log.info("Received notification event: orderId={}, userId={}, status={}",
                event.orderId(), event.userId(), event.status());
        try {
            notificationService.saveNotification(event);
            log.info("Notification event processed successfully: orderId={}", event.orderId());
        } catch (Exception e) {
            log.error("Failed to process notification event: orderId={}, userId={}",
                    event.orderId(), event.userId(), e);
            throw e;
        }
    }
}
