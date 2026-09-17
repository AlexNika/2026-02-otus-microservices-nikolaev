package ru.otus.hw.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import ru.otus.hw.dto.NotificationEvent;
import ru.otus.hw.dto.mapper.NotificationMapper;
import ru.otus.hw.model.Notification;
import ru.otus.hw.model.ProcessedMessage;
import ru.otus.hw.repository.NotificationRepository;
import ru.otus.hw.repository.ProcessedMessageRepository;

import java.math.BigDecimal;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Идемпотентный приём уведомлений по eventId.
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceImplTest {

    private static final String EVENT_ID = "3f2b8c1a-9d4e-4a7f-8b2c-6e1d0a9b5c3d";

    private static final Long ORDER_ID = 100L;

    private static final Long USER_ID = 7L;

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private ProcessedMessageRepository processedMessageRepository;

    @Mock
    private NotificationMapper notificationMapper;

    @Mock
    private ru.otus.hw.metrics.ConsumerMetrics consumerMetrics;

    @InjectMocks
    private NotificationServiceImpl notificationService;

    private NotificationEvent event(String eventId) {
        return NotificationEvent.builder()
                .eventId(eventId)
                .orderId(ORDER_ID)
                .userId(USER_ID)
                .price(new BigDecimal("250.00"))
                .status("PLACED")
                .message("Order placed successfully.")
                .timestamp(Instant.now())
                .build();
    }

    @Test
    @DisplayName("первая доставка события - сохраняется маркер и уведомление")
    void shouldSaveNotificationAndMarkerOnFirstDelivery() {
        NotificationEvent event = event(EVENT_ID);
        Notification notification = Notification.builder().orderId(ORDER_ID).userId(USER_ID).build();
        when(processedMessageRepository.existsById(EVENT_ID)).thenReturn(false);
        when(notificationMapper.toEntity(event)).thenReturn(notification);
        when(notificationRepository.save(any(Notification.class))).thenReturn(notification);

        notificationService.saveNotification(event);

        verify(processedMessageRepository).saveAndFlush(any(ProcessedMessage.class));
        verify(notificationRepository).save(notification);
    }

    @Test
    @DisplayName("повторная доставка того же события - маркер уже есть, дубль уведомления не создаётся")
    void shouldSkipDuplicateWhenMarkerAlreadyExists() {
        NotificationEvent event = event(EVENT_ID);
        when(processedMessageRepository.existsById(EVENT_ID)).thenReturn(true);

        notificationService.saveNotification(event);

        verify(notificationRepository, never()).save(any());
        verify(processedMessageRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("конкурентная вставка маркера (DataIntegrityViolation) - no-op, без исключения и дубля")
    void shouldTreatConcurrentMarkerInsertAsNoOp() {
        NotificationEvent event = event(EVENT_ID);
        when(processedMessageRepository.existsById(EVENT_ID)).thenReturn(false);
        when(processedMessageRepository.saveAndFlush(any(ProcessedMessage.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "duplicate key value violates unique constraint \"pk_processed_messages\""));

        notificationService.saveNotification(event);

        verify(notificationRepository, never()).save(any());
    }

    @Test
    @DisplayName("событие без eventId (legacy) - дедупликация не применяется, уведомление сохраняется")
    void shouldSaveLegacyEventWithoutDeduplication() {
        NotificationEvent event = event(null);
        Notification notification = Notification.builder().orderId(ORDER_ID).userId(USER_ID).build();
        when(notificationMapper.toEntity(event)).thenReturn(notification);
        when(notificationRepository.save(any(Notification.class))).thenReturn(notification);

        notificationService.saveNotification(event);

        verify(processedMessageRepository, never()).existsById(any());
        verify(processedMessageRepository, never()).saveAndFlush(any());
        verify(notificationRepository).save(notification);
    }
}
