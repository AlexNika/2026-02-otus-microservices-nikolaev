package ru.otus.hw.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
import ru.otus.hw.models.OutboxEvent;
import ru.otus.hw.models.OutboxEvent.OutboxStatus;
import ru.otus.hw.repository.OutboxEventRepository;

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
 * Scheduled-вычитка user_outbox:<br>
 * публикация NEW-событий в users.events/user.created, отметка SENT, ретраи и переход в FAILED,
 * возврат SENT -> NEW по nack/returned.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OutboxPublisherTest {

    private static final String EVENT_ID = "1e2f3a4b-5c6d-7e8f-9a0b-1c2d3e4f5a6b";

    private static final String VALID_PAYLOAD = """
            {
              "eventId": "%s",
              "userId": 7,
              "email": "john@example.com",
              "timestamp": "2026-08-12T10:00:00Z"
            }
            """.formatted(EVENT_ID);

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private RabbitMQProperties rabbitMQProperties;

    private OutboxPublisher outboxPublisher;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        outboxPublisher = new OutboxPublisher(outboxEventRepository, rabbitTemplate, rabbitMQProperties,
                objectMapper);
        RabbitMQProperties.ProducerProperties producer = new RabbitMQProperties.ProducerProperties();
        producer.setExchangeName("users.events");
        producer.setRoutingKey("user.created");
        when(rabbitMQProperties.getProducer()).thenReturn(producer);
    }

    private @NonNull OutboxEvent outboxEvent(String payload, OutboxStatus status, int attempts) {
        OutboxEvent event = OutboxEvent.builder()
                .eventId(EVENT_ID)
                .payload(payload)
                .status(status)
                .attempts(attempts)
                .createdAt(LocalDateTime.now().minusSeconds(10))
                .build();
        event.setId(1L);
        return event;
    }

    @Test
    @DisplayName("NEW-событие публикуется в users.events/user.created и помечается SENT с sent_at")
    void shouldPublishNewEventAndMarkSent() {
        OutboxEvent event = outboxEvent(VALID_PAYLOAD, OutboxStatus.NEW, 0);
        when(outboxEventRepository.findByStatusOrderByCreatedAtAsc(OutboxStatus.NEW)).thenReturn(List.of(event));
        when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(inv ->
                inv.getArgument(0));

        outboxPublisher.publishPendingEvents();

        verify(rabbitTemplate).convertAndSend(
                eq("users.events"),
                eq("user.created"),
                any(), any(MessagePostProcessor.class), any(CorrelationData.class));

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(captor.getValue().getSentAt()).isNotNull();
    }

    @Test
    @DisplayName("ошибка брокера - attempts++, событие остаётся NEW для повторной публикации")
    void shouldKeepNewAndIncrementAttemptsOnBrokerError() {
        OutboxEvent event = outboxEvent(VALID_PAYLOAD, OutboxStatus.NEW, 0);
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
    @DisplayName("исчерпан лимит попыток - событие помечается FAILED и не публикуется повторно")
    void shouldMarkFailedAfterMaxAttempts() {
        OutboxEvent event = outboxEvent(VALID_PAYLOAD, OutboxStatus.NEW, 4);
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
    @DisplayName("повреждённый payload - сразу FAILED без бесконечных ретраев")
    void shouldMarkFailedOnCorruptedPayload() {
        OutboxEvent event = outboxEvent("{not a json", OutboxStatus.NEW, 0);
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
    }

    @Test
    @DisplayName("nack/returned: SENT-событие возвращается в NEW для повторной публикации")
    void shouldRequeueSentEventBackToNew() {
        OutboxEvent event = outboxEvent(VALID_PAYLOAD, OutboxStatus.SENT, 0);
        event.setSentAt(LocalDateTime.now());
        when(outboxEventRepository.findByEventId(EVENT_ID)).thenReturn(Optional.of(event));
        when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(inv ->
                inv.getArgument(0));

        outboxPublisher.requeueByEventId(EVENT_ID, "nack: test");

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(OutboxStatus.NEW);
        assertThat(captor.getValue().getSentAt()).isNull();
    }

    @Test
    @DisplayName("nack/returned: событие уже NEW - повторная запись не выполняется")
    void shouldNotTouchAlreadyNewEventOnRequeue() {
        OutboxEvent event = outboxEvent(VALID_PAYLOAD, OutboxStatus.NEW, 0);
        when(outboxEventRepository.findByEventId(EVENT_ID)).thenReturn(Optional.of(event));

        outboxPublisher.requeueByEventId(EVENT_ID, "nack: test");

        verify(outboxEventRepository, never()).save(any());
    }
}
