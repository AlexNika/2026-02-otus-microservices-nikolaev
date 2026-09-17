package ru.otus.hw.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import ru.otus.hw.dto.UserSyncEvent;
import ru.otus.hw.model.NotificationContact;
import ru.otus.hw.model.ProcessedMessage;
import ru.otus.hw.repository.NotificationContactRepository;
import ru.otus.hw.repository.ProcessedMessageRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Применение UserSyncEvent к read-модели контактов NOTIFICATIONService:
 * идемпотентность по eventId (processed_messages), timestamp guard, upsert email/phone.
 */
@ExtendWith(MockitoExtension.class)
class ContactSyncServiceTest {

    private static final String EVENT_ID = "4f3b2a1c-0d9e-8f7a-6b5c-4d3e2f1a0b9c";

    private static final Long USER_ID = 7L;

    @Mock
    private NotificationContactRepository contactRepository;

    @Mock
    private ProcessedMessageRepository processedMessageRepository;

    @Mock
    private ru.otus.hw.metrics.ConsumerMetrics consumerMetrics;

    @InjectMocks
    private ContactSyncService contactSyncService;

    private UserSyncEvent event(String eventId, Instant updatedAt, String email, String phone) {
        return UserSyncEvent.builder()
                .eventId(eventId)
                .userId(USER_ID)
                .email(email)
                .phone(phone)
                .addresses(List.of())
                .updatedAt(updatedAt)
                .build();
    }

    @Test
    @DisplayName("первое событие: сохраняется маркер и запись контактов")
    void shouldCreateContactOnFirstEvent() {
        Instant now = Instant.parse("2026-08-12T10:00:00Z");
        when(processedMessageRepository.existsById(EVENT_ID)).thenReturn(false);
        when(contactRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());

        contactSyncService.applyUserSync(event(EVENT_ID, now, "john@example.com", "+79991234567"));

        verify(processedMessageRepository).saveAndFlush(any(ProcessedMessage.class));
        ArgumentCaptor<NotificationContact> captor = ArgumentCaptor.forClass(NotificationContact.class);
        verify(contactRepository).save(captor.capture());
        NotificationContact contact = captor.getValue();
        assertThat(contact.getUserId()).isEqualTo(USER_ID);
        assertThat(contact.getEmail()).isEqualTo("john@example.com");
        assertThat(contact.getPhone()).isEqualTo("+79991234567");
        assertThat(contact.getUpdatedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("второе событие с новыми email/phone: запись обновляется, дубль не создаётся")
    void shouldUpdateSingleContactRecordOnSecondEvent() {
        Instant firstTime = Instant.parse("2026-08-12T10:00:00Z");
        Instant secondTime = Instant.parse("2026-08-12T11:00:00Z");
        NotificationContact existing = NotificationContact.builder()
                .userId(USER_ID)
                .email("john@example.com")
                .phone("+79991234567")
                .updatedAt(firstTime)
                .build();
        when(processedMessageRepository.existsById(EVENT_ID)).thenReturn(false);
        when(contactRepository.findByUserId(USER_ID)).thenReturn(Optional.of(existing));

        contactSyncService.applyUserSync(event(EVENT_ID, secondTime, "new@example.com", "+79990000000"));

        ArgumentCaptor<NotificationContact> captor = ArgumentCaptor.forClass(NotificationContact.class);
        verify(contactRepository).save(captor.capture());
        assertThat(captor.getValue()).isSameAs(existing);
        assertThat(captor.getValue().getEmail()).isEqualTo("new@example.com");
        assertThat(captor.getValue().getPhone()).isEqualTo("+79990000000");
        assertThat(captor.getValue().getUpdatedAt()).isEqualTo(secondTime);
    }

    @Test
    @DisplayName("повторная доставка того же eventId: no-op по маркеру processed_messages")
    void shouldSkipDuplicateEventByProcessedMarker() {
        when(processedMessageRepository.existsById(EVENT_ID)).thenReturn(true);

        contactSyncService.applyUserSync(event(EVENT_ID, Instant.now(), "john@example.com", null));

        verify(processedMessageRepository, never()).saveAndFlush(any(ProcessedMessage.class));
        verify(contactRepository, never()).save(any(NotificationContact.class));
    }

    @Test
    @DisplayName("конкурентная доставка: маркер уже вставлен другим потоком - обрабатывается как no-op")
    void shouldTreatConcurrentMarkerInsertAsNoOp() {
        when(processedMessageRepository.existsById(EVENT_ID)).thenReturn(false);
        when(processedMessageRepository.saveAndFlush(any(ProcessedMessage.class)))
                .thenThrow(new DataIntegrityViolationException("processed_messages_pkey"));

        contactSyncService.applyUserSync(event(EVENT_ID, Instant.now(), "john@example.com", null));

        verify(contactRepository, never()).save(any(NotificationContact.class));
    }

    @Test
    @DisplayName("более старое событие (updatedAt не новее локального): skip, запись не меняется")
    void shouldSkipStaleEventByTimestampGuard() {
        NotificationContact newer = NotificationContact.builder()
                .userId(USER_ID)
                .email("fresh@example.com")
                .phone("+79990000000")
                .updatedAt(Instant.parse("2026-08-12T11:00:00Z"))
                .build();
        when(processedMessageRepository.existsById(EVENT_ID)).thenReturn(false);
        when(contactRepository.findByUserId(USER_ID)).thenReturn(Optional.of(newer));

        contactSyncService.applyUserSync(event(EVENT_ID, Instant.parse("2026-08-12T10:00:00Z"),
                "stale@example.com", "+79991111111"));

        verify(contactRepository, never()).save(any(NotificationContact.class));
        assertThat(newer.getEmail()).isEqualTo("fresh@example.com");
    }

    @Test
    @DisplayName("phone=null корректно сохраняется")
    void shouldPersistNullPhone() {
        when(processedMessageRepository.existsById(EVENT_ID)).thenReturn(false);
        when(contactRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());

        contactSyncService.applyUserSync(event(EVENT_ID, Instant.now(), "john@example.com", null));

        ArgumentCaptor<NotificationContact> captor = ArgumentCaptor.forClass(NotificationContact.class);
        verify(contactRepository).save(captor.capture());
        assertThat(captor.getValue().getPhone()).isNull();
    }
}
