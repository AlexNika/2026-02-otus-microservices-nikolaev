package ru.otus.hw.producer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.otus.hw.dto.NotificationEvent;
import ru.otus.hw.models.OutboxEvent;
import ru.otus.hw.repository.OutboxEventRepository;
import ru.otus.hw.service.OutboxPublisher;
import ru.otus.hw.tracing.W3CTraceContextAdapter;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Транзакционный outbox-аппендер канала уведомлений.
 *
 * <p>Вместо прямой публикации в RabbitMQ событие записывается в таблицу
 * {@code notification_outbox}; фактическую доставку выполняет {@link OutboxPublisher}.
 * Метод вызывается в одной транзакции с финальным сохранением заказа, поэтому
 * "заказ зафиксирован, а событие потеряно" невозможно.
 *
 * <p>Запись идемпотентна по eventId: повтор (например, при recovery саги) не создаёт дубль.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationEventPublisher {

    private final OutboxEventRepository outboxEventRepository;

    private final ObjectMapper objectMapper;

    private final W3CTraceContextAdapter traceContextAdapter;

    /**
     * Сохраняет событие в outbox (в транзакции вызывающего кода).
     */
    @Transactional
    public void send(@NonNull NotificationEvent event) {
        if (outboxEventRepository.existsByEventId(event.eventId())) {
            log.info("Outbox already contains notification event eventId={}, skipping duplicate", event.eventId());
            return;
        }
        try {
            Map<String, String> traceHeaders = traceContextAdapter.captureCurrent();
            OutboxEvent outboxEvent = OutboxEvent.builder()
                    .eventId(event.eventId())
                    .payload(objectMapper.writeValueAsString(event))
                    .traceparent(traceHeaders.get("traceparent"))
                    .tracestate(traceHeaders.get("tracestate"))
                    .status(OutboxEvent.OutboxStatus.NEW)
                    .attempts(0)
                    .createdAt(LocalDateTime.now())
                    .build();
            outboxEventRepository.save(outboxEvent);
            log.info("Notification event appended to outbox: orderId={}, userId={}, status={}, eventId={}",
                    event.orderId(), event.userId(), event.status(), event.eventId());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize notification event for outbox, eventId="
                    + event.eventId(), e);
        }
    }
}
