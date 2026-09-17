package ru.otus.hw.producer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.otus.hw.dto.UserSyncEvent;
import ru.otus.hw.models.OutboxEvent;
import ru.otus.hw.models.OutboxEvent.EventType;
import ru.otus.hw.models.OutboxEvent.OutboxStatus;
import ru.otus.hw.repository.OutboxEventRepository;
import ru.otus.hw.tracing.W3CTraceContextAdapter;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Транзакционный outbox-аппендер события UserSyncEvent (USER → NOTIFICATION, DELIVERY):
 * полный снимок контактов и адресов доставки пользователя.
 *
 * <p>Событие записывается в общую таблицу {@code user_outbox} с типом {@link EventType#USER_SYNC};
 * фактическую доставку в {@code user.sync.events}/{@code user.profile.sync} выполняет
 * {@code OutboxPublisher}. Метод вызывается в одной транзакции с бизнес-изменением, поэтому
 * "данные изменены, а событие потеряно" невозможно.
 *
 * <p>Запись идемпотентна по eventId: повтор не создаёт дубль.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserSyncEventPublisher {

    private final OutboxEventRepository outboxEventRepository;

    private final W3CTraceContextAdapter traceContextAdapter;

    private final ObjectMapper objectMapper;

    /**
     * Сохраняет событие в outbox (в транзакции вызывающего кода).
     */
    @Transactional
    public void send(@NonNull UserSyncEvent event) {
        if (outboxEventRepository.existsByEventId(event.eventId())) {
            log.info("Outbox already contains user-sync event eventId={}, skipping duplicate", event.eventId());
            return;
        }
        try {
            Map<String, String> traceHeaders = traceContextAdapter.captureCurrent();
            OutboxEvent outboxEvent = OutboxEvent.builder()
                    .eventId(event.eventId())
                    .eventType(EventType.USER_SYNC)
                    .payload(objectMapper.writeValueAsString(event))
                    .traceparent(traceHeaders.get("traceparent"))
                    .tracestate(traceHeaders.get("tracestate"))
                    .status(OutboxStatus.NEW)
                    .attempts(0)
                    .createdAt(LocalDateTime.now())
                    .build();
            outboxEventRepository.save(outboxEvent);
            log.info("UserSyncEvent appended to outbox: userId={}, eventId={}",
                    event.userId(), event.eventId());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize user-sync event for outbox, eventId="
                    + event.eventId(), e);
        }
    }
}
