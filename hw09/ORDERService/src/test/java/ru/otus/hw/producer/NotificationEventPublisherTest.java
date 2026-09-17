package ru.otus.hw.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.otus.hw.dto.NotificationEvent;
import ru.otus.hw.models.OutboxEvent;
import ru.otus.hw.repository.OutboxEventRepository;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * NotificationEventPublisher как outbox-аппендер:<br>
 * Вместо прямой публикации в RabbitMQ событие записывается в notification_outbox.
 */
@ExtendWith(MockitoExtension.class)
class NotificationEventPublisherTest {

    private static final String EVENT_ID = "9b1c2d3e-4f5a-6b7c-8d9e-0f1a2b3c4d5e";

    @Mock
    private OutboxEventRepository outboxEventRepository;

    private NotificationEventPublisher publisher;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        publisher = new NotificationEventPublisher(outboxEventRepository, objectMapper);
    }

    private NotificationEvent event() {
        return NotificationEvent.builder()
                .eventId(EVENT_ID)
                .orderId(100L)
                .userId(7L)
                .price(new BigDecimal("250.00"))
                .status("PLACED")
                .message("Order placed successfully. Payment confirmed.")
                .timestamp(Instant.now())
                .build();
    }

    @Test
    @DisplayName("событие сохраняется в outbox со статусом NEW и JSON-payload")
    void shouldAppendEventToOutboxAsNew() {
        NotificationEvent event = event();
        when(outboxEventRepository.existsByEventId(EVENT_ID)).thenReturn(false);
        when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        publisher.send(event);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());
        OutboxEvent saved = captor.getValue();
        assertThat(saved.getEventId()).isEqualTo(EVENT_ID);
        assertThat(saved.getStatus()).isEqualTo(OutboxEvent.OutboxStatus.NEW);
        assertThat(saved.getAttempts()).isZero();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getSentAt()).isNull();
        assertThat(saved.getPayload()).contains("\"orderId\":100").contains("\"status\":\"PLACED\"");
    }

    @Test
    @DisplayName("повторная запись того же eventId - дубль в outbox не создаётся")
    void shouldSkipDuplicateEventInOutbox() {
        when(outboxEventRepository.existsByEventId(EVENT_ID)).thenReturn(true);

        publisher.send(event());

        verify(outboxEventRepository, never()).save(any());
    }
}
