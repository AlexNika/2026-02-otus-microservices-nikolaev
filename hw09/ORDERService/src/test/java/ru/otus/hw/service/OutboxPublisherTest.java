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
import ru.otus.hw.config.properties.RabbitMQConfig;
import ru.otus.hw.models.OutboxEvent;
import ru.otus.hw.models.OutboxEvent.OutboxStatus;
import ru.otus.hw.repository.OutboxEventRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Scheduled-вычитка notification_outbox:<br>
 * Публикация NEW-событий, отметка SENT, ретраи и переход в FAILED.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OutboxPublisherTest {

    private static final String EVENT_ID = "9b1c2d3e-4f5a-6b7c-8d9e-0f1a2b3c4d5e";

    private static final String VALID_PAYLOAD = """
            {
              "eventId": "%s",
              "orderId": 100,
              "userId": 7,
              "price": 250.00,
              "status": "PLACED",
              "message": "Order placed successfully. Payment confirmed.",
              "timestamp": "2026-08-12T10:00:00Z"
            }
            """.formatted(EVENT_ID);

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private RabbitMQConfig rabbitMQConfig;

    private OutboxPublisher outboxPublisher;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        outboxPublisher = new OutboxPublisher(outboxEventRepository, rabbitTemplate, rabbitMQConfig, objectMapper);
        when(rabbitMQConfig.getExchangeName()).thenReturn("hw09.direct");
        when(rabbitMQConfig.getRoutingKey()).thenReturn("notification.event");
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
    @DisplayName("NEW-событие публикуется в RabbitMQ и помечается SENT с sent_at")
    void shouldPublishNewEventAndMarkSent() {
        OutboxEvent event = outboxEvent(VALID_PAYLOAD, OutboxStatus.NEW, 0);
        when(outboxEventRepository.findByStatusOrderByCreatedAtAsc(OutboxStatus.NEW)).thenReturn(List.of(event));
        when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(inv ->
                inv.getArgument(0));

        outboxPublisher.publishPendingEvents();

        verify(rabbitTemplate).convertAndSend(
                org.mockito.ArgumentMatchers.eq("hw09.direct"),
                org.mockito.ArgumentMatchers.eq("notification.event"),
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
