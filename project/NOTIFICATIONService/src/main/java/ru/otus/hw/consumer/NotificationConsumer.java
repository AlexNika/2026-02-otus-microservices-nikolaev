package ru.otus.hw.consumer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Headers;
import org.springframework.stereotype.Component;
import ru.otus.hw.dto.NotificationEvent;
import ru.otus.hw.metrics.ConsumerMetrics;
import ru.otus.hw.service.NotificationService;
import ru.otus.hw.tracing.W3CTraceContextAdapter;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationConsumer {

    private final NotificationService notificationService;

    private final ConsumerMetrics consumerMetrics;

    private final W3CTraceContextAdapter traceContextAdapter;

    @RabbitListener(queues = "${app.rabbitmq.queue-name:notification.queue}")
    public void handleNotification(@NonNull NotificationEvent event, @Headers Map<String, Object> amqpHeaders) {
        try (W3CTraceContextAdapter.Scope ignored =
                traceContextAdapter.open(amqpHeaders, "notification.notification.consume")) {
            log.info("Received notification event: orderId={}, userId={}, status={}",
                    event.orderId(), event.userId(), event.status());
            consumerMetrics.notificationReceived(event.status());
            try {
                notificationService.saveNotification(event);
                log.info("Notification event processed successfully: orderId={}", event.orderId());
            } catch (Exception e) {
                consumerMetrics.processed(ConsumerMetrics.CONSUMER_NOTIFICATION, ConsumerMetrics.RESULT_FAILED);
                log.error("Failed to process notification event: orderId={}, userId={}",
                        event.orderId(), event.userId(), e);
                throw e;
            }
        }
    }
}
