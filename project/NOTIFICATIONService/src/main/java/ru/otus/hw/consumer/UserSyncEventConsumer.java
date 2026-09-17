package ru.otus.hw.consumer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import ru.otus.hw.dto.UserSyncEvent;
import ru.otus.hw.metrics.ConsumerMetrics;
import ru.otus.hw.service.ContactSyncService;

/**
 * Приём UserSyncEvent (канал USER → NOTIFICATION, user.sync.events / user.profile.sync)
 * и обновление локальной read-модели контактов.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserSyncEventConsumer {

    private final ContactSyncService contactSyncService;

    private final ConsumerMetrics consumerMetrics;

    @RabbitListener(queues = "${app.rabbitmq.user-sync.queue-name:notification.profile-sync.queue}")
    public void handleUserSync(@NonNull UserSyncEvent event) {
        log.info("Received user-sync event: userId={}, eventId={}", event.userId(), event.eventId());
        try {
            contactSyncService.applyUserSync(event);
            log.info("User-sync event processed successfully: userId={}, eventId={}",
                    event.userId(), event.eventId());
        } catch (Exception e) {
            consumerMetrics.processed(ConsumerMetrics.CONSUMER_USER_SYNC, ConsumerMetrics.RESULT_FAILED);
            log.error("Failed to process user-sync event: userId={}, eventId={}",
                    event.userId(), event.eventId(), e);
            throw e;
        }
    }
}
