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
import ru.otus.hw.dto.UserAddressDto;
import ru.otus.hw.dto.UserSyncEvent;
import ru.otus.hw.models.OutboxEvent;
import ru.otus.hw.models.OutboxEvent.EventType;
import ru.otus.hw.models.OutboxEvent.OutboxStatus;
import ru.otus.hw.repository.OutboxEventRepository;
import ru.otus.hw.tracing.W3CTraceContextAdapter;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Outbox-аппендер UserSyncEvent: идемпотентность записи по eventId,
 * payload = JSON полного снимка, тип события USER_SYNC.
 */
@ExtendWith(MockitoExtension.class)
class UserSyncEventPublisherTest {

    private static final String EVENT_ID = "9c8d7e6f-5a4b-3c2d-1e0f-9a8b7c6d5e4f";

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private W3CTraceContextAdapter traceContextAdapter;

    private UserSyncEventPublisher publisher;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @BeforeEach
    void setUp() {
        publisher = new UserSyncEventPublisher(outboxEventRepository, traceContextAdapter, objectMapper);
    }

    private UserSyncEvent event() {
        return UserSyncEvent.builder()
                .eventId(EVENT_ID)
                .userId(7L)
                .email("john@example.com")
                .phone("+79991234567")
                .addresses(List.of(UserAddressDto.builder()
                        .addressId(1L)
                        .fullAddress("Moscow, Tverskaya st. 7")
                        .city("Moscow")
                        .postalCode("125009")
                        .isDefault(true)
                        .build()))
                .updatedAt(Instant.parse("2026-08-12T10:00:00Z"))
                .build();
    }

    @Test
    @DisplayName("событие записывается в outbox с типом USER_SYNC, статусом NEW и JSON-снимком")
    void shouldAppendUserSyncEventToOutbox() {
        when(outboxEventRepository.existsByEventId(EVENT_ID)).thenReturn(false);

        publisher.send(event());

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());
        OutboxEvent outboxEvent = captor.getValue();
        assertThat(outboxEvent.getEventId()).isEqualTo(EVENT_ID);
        assertThat(outboxEvent.getEventType()).isEqualTo(EventType.USER_SYNC);
        assertThat(outboxEvent.getStatus()).isEqualTo(OutboxStatus.NEW);
        assertThat(outboxEvent.getAttempts()).isZero();
        assertThat(outboxEvent.getPayload()).isEqualToIgnoringWhitespace("""
                {
                  "eventId": "%s",
                  "userId": 7,
                  "email": "john@example.com",
                  "phone": "+79991234567",
                  "addresses": [
                    {
                      "addressId": 1,
                      "fullAddress": "Moscow, Tverskaya st. 7",
                      "city": "Moscow",
                      "postalCode": "125009",
                      "isDefault": true,
                      "deliveryPreferences": null
                    }
                  ],
                  "updatedAt": "2026-08-12T10:00:00Z"
                }
                """.formatted(EVENT_ID));
    }

    @Test
    @DisplayName("outbox уже содержит событие с тем же eventId - дубль не создаётся")
    void shouldSkipDuplicateWhenEventAlreadyInOutbox() {
        when(outboxEventRepository.existsByEventId(EVENT_ID)).thenReturn(true);

        publisher.send(event());

        verify(outboxEventRepository, never()).save(org.mockito.ArgumentMatchers.any(OutboxEvent.class));
    }
}
