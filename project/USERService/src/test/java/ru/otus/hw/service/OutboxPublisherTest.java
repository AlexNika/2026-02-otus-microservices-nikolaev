package ru.otus.hw.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import ru.otus.hw.config.properties.RabbitMQProperties;
import ru.otus.hw.dto.UserSyncEvent;
import ru.otus.hw.models.OutboxEvent;
import ru.otus.hw.models.OutboxEvent.EventType;
import ru.otus.hw.models.OutboxEvent.OutboxStatus;
import ru.otus.hw.repository.OutboxEventRepository;
import ru.otus.hw.tracing.W3CTraceContextAdapter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Scheduled-вычитка user_outbox после переноса аутентификации в AuthService:
 * единственный публикуемый тип - USER_SYNC; SENT выставляется только по confirm(ack)
 * брокера (проверка markSentByEventId/requeueIfAlreadySent напрямую).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OutboxPublisherTest {

    private static final String EVENT_ID = "1e2f3a4b-5c6d-7e8f-9a0b-1c2d3e4f5a6b";

    private static final String VALID_USER_SYNC_PAYLOAD = """
            {
              "eventId": "%s",
              "userId": 7,
              "email": "john@example.com",
              "phone": "+79991234567",
              "addresses": [
                {"addressId": 1, "fullAddress": "Moscow, Tverskaya st. 7",
                 "city": "Moscow", "postalCode": "125009", "isDefault": true,
                 "deliveryPreferences": null}
              ],
              "updatedAt": "2026-08-12T10:00:00Z"
            }
            """.formatted(EVENT_ID);

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private RabbitMQProperties rabbitMQProperties;

    @Mock
    private W3CTraceContextAdapter traceContextAdapter;

    private SimpleMeterRegistry meterRegistry;

    private OutboxPublisher outboxPublisher;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        meterRegistry = new SimpleMeterRegistry();
        outboxPublisher = new OutboxPublisher(outboxEventRepository, rabbitTemplate, rabbitMQProperties,
                objectMapper, traceContextAdapter, meterRegistry);
        outboxPublisher.registerDeliveryCallbacks();
        RabbitMQProperties.UserSyncProperties userSync = new RabbitMQProperties.UserSyncProperties();
        userSync.setExchangeName("user.sync.events");
        userSync.setRoutingKey("user.profile.sync");
        when(rabbitMQProperties.getUserSync()).thenReturn(userSync);
    }

    private @NonNull OutboxEvent outboxEvent(String payload, EventType eventType, OutboxStatus status,
                                             int attempts) {
        OutboxEvent event = OutboxEvent.builder()
                .eventId(EVENT_ID)
                .eventType(eventType)
                .payload(payload)
                .status(status)
                .attempts(attempts)
                .createdAt(LocalDateTime.now().minusSeconds(10))
                .build();
        event.setId(1L);
        return event;
    }

    @Test
    @DisplayName("NEW user-sync событие отправляется в user.sync.events/user.profile.sync; "
            + "SENT без confirm брокера не выставляется")
    void shouldPublishUserSyncEventWithoutImmediateSentMark() {
        OutboxEvent event = outboxEvent(VALID_USER_SYNC_PAYLOAD, EventType.USER_SYNC,
                OutboxStatus.NEW, 0);
        when(outboxEventRepository.findByStatusOrderByCreatedAtAsc(OutboxStatus.NEW)).thenReturn(List.of(event));

        outboxPublisher.publishPendingEvents();

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate).convertAndSend(
                eq("user.sync.events"),
                eq("user.profile.sync"),
                payloadCaptor.capture(), any(MessagePostProcessor.class), any(CorrelationData.class));
        assertThat(payloadCaptor.getValue()).isInstanceOf(UserSyncEvent.class);
        UserSyncEvent published = (UserSyncEvent) payloadCaptor.getValue();
        assertThat(published.userId()).isEqualTo(7L);
        assertThat(published.email()).isEqualTo("john@example.com");
        assertThat(published.phone()).isEqualTo("+79991234567");
        assertThat(published.addresses()).hasSize(1);
        assertThat(published.addresses().get(0).addressId()).isEqualTo(1L);

        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
    }

    @Test
    @DisplayName("confirm(ack) брокера помечает NEW-событие SENT с sent_at")
    void shouldMarkSentOnBrokerConfirm() {
        OutboxEvent event = outboxEvent(VALID_USER_SYNC_PAYLOAD, EventType.USER_SYNC,
                OutboxStatus.NEW, 0);
        when(outboxEventRepository.findByEventId(EVENT_ID)).thenReturn(Optional.of(event));
        when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        outboxPublisher.markSentByEventId(EVENT_ID);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(captor.getValue().getSentAt()).isNotNull();
    }

    @Test
    @DisplayName("confirm для события не в статусе NEW - повторная запись не выполняется")
    void shouldNotTouchNonNewEventOnConfirm() {
        OutboxEvent event = outboxEvent(VALID_USER_SYNC_PAYLOAD, EventType.USER_SYNC,
                OutboxStatus.SENT, 0);
        when(outboxEventRepository.findByEventId(EVENT_ID)).thenReturn(Optional.of(event));

        outboxPublisher.markSentByEventId(EVENT_ID);

        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("returned после confirm: SENT-событие возвращается в NEW для повторной публикации")
    void shouldRequeueSentEventOnLateReturn() {
        OutboxEvent event = outboxEvent(VALID_USER_SYNC_PAYLOAD, EventType.USER_SYNC,
                OutboxStatus.SENT, 0);
        event.setSentAt(LocalDateTime.now());
        when(outboxEventRepository.findByEventId(EVENT_ID)).thenReturn(Optional.of(event));
        when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        outboxPublisher.requeueIfAlreadySent(EVENT_ID);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(OutboxStatus.NEW);
        assertThat(captor.getValue().getSentAt()).isNull();
    }

    @Test
    @DisplayName("returned до confirm: событие ещё NEW - повторная запись не выполняется")
    void shouldNotTouchNewEventOnReturn() {
        OutboxEvent event = outboxEvent(VALID_USER_SYNC_PAYLOAD, EventType.USER_SYNC,
                OutboxStatus.NEW, 0);
        when(outboxEventRepository.findByEventId(EVENT_ID)).thenReturn(Optional.of(event));

        outboxPublisher.requeueIfAlreadySent(EVENT_ID);

        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("ошибка брокера при отправке - attempts++, событие остаётся NEW")
    void shouldKeepNewAndIncrementAttemptsOnBrokerError() {
        OutboxEvent event = outboxEvent(VALID_USER_SYNC_PAYLOAD, EventType.USER_SYNC,
                OutboxStatus.NEW, 0);
        when(outboxEventRepository.findByStatusOrderByCreatedAtAsc(OutboxStatus.NEW)).thenReturn(List.of(event));
        when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(inv ->
                inv.getArgument(0));
        doThrow(new AmqpException("broker unavailable"))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(),
                        any(MessagePostProcessor.class), any(CorrelationData.class));

        outboxPublisher.publishPendingEvents();

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(OutboxStatus.NEW);
        assertThat(captor.getValue().getAttempts()).isEqualTo(1);
        assertThat(captor.getValue().getSentAt()).isNull();
    }

    @Test
    @DisplayName("исчерпан лимит попыток - событие помечается FAILED")
    void shouldMarkFailedAfterMaxAttempts() {
        OutboxEvent event = outboxEvent(VALID_USER_SYNC_PAYLOAD, EventType.USER_SYNC,
                OutboxStatus.NEW, 4);
        when(outboxEventRepository.findByStatusOrderByCreatedAtAsc(OutboxStatus.NEW)).thenReturn(List.of(event));
        when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(inv ->
                inv.getArgument(0));
        doThrow(new AmqpException("broker unavailable"))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(),
                        any(MessagePostProcessor.class), any(CorrelationData.class));

        outboxPublisher.publishPendingEvents();

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(captor.getValue().getAttempts()).isEqualTo(5);
    }

    @Test
    @DisplayName("повреждённый user-sync payload - сразу FAILED, в user.sync.events ничего не уходит")
    void shouldMarkFailedOnCorruptedUserSyncPayload() {
        OutboxEvent event = outboxEvent("{not a json", EventType.USER_SYNC, OutboxStatus.NEW, 0);
        when(outboxEventRepository.findByStatusOrderByCreatedAtAsc(OutboxStatus.NEW)).thenReturn(List.of(event));
        when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(inv ->
                inv.getArgument(0));

        outboxPublisher.publishPendingEvents();

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(OutboxStatus.FAILED);
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(),
                any(MessagePostProcessor.class), any(CorrelationData.class));
    }

    @Test
    @DisplayName("legacy USER_CREATED в outbox USERService - помечается FAILED "
            + "(публикация типа теперь в AuthService)")
    void shouldMarkFailedForLegacyUserCreatedEvent() {
        OutboxEvent event = outboxEvent(VALID_USER_SYNC_PAYLOAD, EventType.USER_CREATED, OutboxStatus.NEW, 0);
        when(outboxEventRepository.findByStatusOrderByCreatedAtAsc(OutboxStatus.NEW)).thenReturn(List.of(event));
        when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(inv ->
                inv.getArgument(0));

        outboxPublisher.publishPendingEvents();

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(OutboxStatus.FAILED);
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(),
                any(MessagePostProcessor.class), any(CorrelationData.class));
    }

    @Test
    @DisplayName("нет NEW-событий - публикация не выполняется")
    void shouldDoNothingWhenOutboxIsEmpty() {
        when(outboxEventRepository.findByStatusOrderByCreatedAtAsc(OutboxStatus.NEW)).thenReturn(List.of());

        outboxPublisher.publishPendingEvents();

        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(),
                any(MessagePostProcessor.class), any(CorrelationData.class));
        verify(outboxEventRepository, never()).save(any());
        assertThat(meterRegistry.find("outbox.events.pending").gauge().value()).isZero();
    }

    @Test
    @DisplayName("метрики outbox: published-счётчик по confirm(ack), pending-датчик по вычитке")
    void shouldTrackPublishedCounterAndPendingGauge() {
        OutboxEvent pending = outboxEvent(VALID_USER_SYNC_PAYLOAD, EventType.USER_SYNC, OutboxStatus.NEW, 0);
        when(outboxEventRepository.findByStatusOrderByCreatedAtAsc(OutboxStatus.NEW)).thenReturn(List.of(pending));
        when(outboxEventRepository.findByEventId(EVENT_ID)).thenReturn(Optional.of(pending));
        when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        outboxPublisher.publishPendingEvents();
        assertThat(meterRegistry.find("outbox.events.pending").gauge().value()).isEqualTo(1.0);

        outboxPublisher.markSentByEventId(EVENT_ID);
        assertThat(meterRegistry.find("outbox.events.published").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("метрики outbox: failed-счётчик после исчерпания попыток")
    void shouldTrackFailedCounterAfterAttemptsExhausted() {
        OutboxEvent event = outboxEvent(VALID_USER_SYNC_PAYLOAD, EventType.USER_SYNC, OutboxStatus.NEW, 4);
        when(outboxEventRepository.findByStatusOrderByCreatedAtAsc(OutboxStatus.NEW)).thenReturn(List.of(event));
        when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new AmqpException("broker unavailable"))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(),
                        any(MessagePostProcessor.class), any(CorrelationData.class));

        outboxPublisher.publishPendingEvents();

        assertThat(meterRegistry.find("outbox.events.failed").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.find("outbox.events.published").counter().count()).isZero();
    }

    @Test
    @DisplayName("метрики outbox: requeued-счётчик при возврате SENT-события в NEW")
    void shouldTrackRequeuedCounterOnLateReturn() {
        OutboxEvent event = outboxEvent(VALID_USER_SYNC_PAYLOAD, EventType.USER_SYNC, OutboxStatus.SENT, 0);
        when(outboxEventRepository.findByEventId(EVENT_ID)).thenReturn(Optional.of(event));
        when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        outboxPublisher.requeueIfAlreadySent(EVENT_ID);

        assertThat(meterRegistry.find("outbox.events.requeued").counter().count()).isEqualTo(1.0);
    }
}
