package ru.otus.hw.consumer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Headers;
import org.springframework.stereotype.Component;
import ru.otus.hw.dto.UserSyncEvent;
import ru.otus.hw.service.DeliveryAddressSyncService;
import ru.otus.hw.tracing.W3CTraceContextAdapter;

import java.util.Map;

/**
 * Приём UserSyncEvent (канал USER → DELIVERY, user.sync.events / user.profile.sync)
 * и обновление локальной read-модели адресов доставки.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserSyncEventConsumer {

    private final DeliveryAddressSyncService deliveryAddressSyncService;

    private final W3CTraceContextAdapter traceContextAdapter;

    @RabbitListener(queues = "${app.rabbitmq.user-sync.queue-name:delivery.profile-sync.queue}")
    public void handleUserSync(@NonNull UserSyncEvent event, @Headers Map<String, Object> amqpHeaders) {
        try (W3CTraceContextAdapter.Scope ignored =
                traceContextAdapter.open(amqpHeaders, "delivery.user-sync.consume")) {
            log.info("Received user-sync event: userId={}, eventId={}", event.userId(), event.eventId());
            try {
                deliveryAddressSyncService.applyUserSync(event);
                log.info("User-sync event processed successfully: userId={}, eventId={}",
                        event.userId(), event.eventId());
            } catch (Exception e) {
                log.error("Failed to process user-sync event: userId={}, eventId={}",
                        event.userId(), event.eventId(), e);
                throw e;
            }
        }
    }
}
