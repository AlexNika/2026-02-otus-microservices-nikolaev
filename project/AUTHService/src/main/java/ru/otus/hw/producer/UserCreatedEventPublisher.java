package ru.otus.hw.producer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.otus.hw.dto.UserCreatedEvent;
import ru.otus.hw.models.OutboxEvent;
import ru.otus.hw.models.OutboxEvent.OutboxStatus;
import ru.otus.hw.repository.OutboxEventRepository;
import ru.otus.hw.tracing.W3CTraceContextAdapter;

import java.util.Map;

/**
 * Транзакционный outbox-аппендер события UserCreatedEvent (AUTH → USER/BILLING).
 *
 * <p>Вместо прямой публикации в RabbitMQ событие записывается в таблицу {@code auth_outbox};
 * фактическую доставку выполняет {@code OutboxPublisher}. Метод вызывается в одной транзакции
 * с сохранением credentials, поэтому "credentials сохранены, а событие потеряно" невозможно.
 *
 * <p>Запись идемпотентна по eventId: повтор не создаёт дубль.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserCreatedEventPublisher {

    private final OutboxEventRepository outboxEventRepository;

    private final W3CTraceContextAdapter traceContextAdapter;

    private final ObjectMapper objectMapper;

    /**
     * Сохраняет событие в outbox (в транзакции вызывающего кода).
     */
    @Transactional
    public void send(@NonNull UserCreatedEvent event) {
        if (outboxEventRepository.existsByEventId(event.eventId())) {
            log.info("Outbox already contains user-created event eventId={}, skipping duplicate", event.eventId());
            return;
        }
        try {
            Map<String, String> traceHeaders = traceContextAdapter.captureCurrent();
            OutboxEvent outboxEvent = OutboxEvent.builder()
                    .eventId(event.eventId())
                    .payload(objectMapper.writeValueAsString(event))
                    .traceparent(traceHeaders.get("traceparent"))
                    .tracestate(traceHeaders.get("tracestate"))
                    .status(OutboxStatus.NEW)
                    .attempts(0)
                    .build();
            outboxEventRepository.save(outboxEvent);
            log.info("UserCreatedEvent appended to outbox: userId={}, eventId={}",
                    event.userId(), event.eventId());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize user-created event for outbox, eventId="
                    + event.eventId(), e);
        }
    }
}
