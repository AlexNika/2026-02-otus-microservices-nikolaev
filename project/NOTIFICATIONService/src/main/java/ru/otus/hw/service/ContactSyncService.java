package ru.otus.hw.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.otus.hw.dto.UserSyncEvent;
import ru.otus.hw.metrics.ConsumerMetrics;
import ru.otus.hw.model.NotificationContact;
import ru.otus.hw.model.ProcessedMessage;
import ru.otus.hw.repository.NotificationContactRepository;
import ru.otus.hw.repository.ProcessedMessageRepository;

import java.time.LocalDateTime;

/**
 * Применение {@link UserSyncEvent} к локальной read-модели контактов
 * (таблица {@code notification_contacts}, 1:1 по user_id).
 *
 * <p>Идемпотентность: dedup по {@code eventId} через существующую таблицу
 * {@code processed_messages} (паттерн {@link NotificationServiceImpl}).
 * Защита от out-of-order доставки: событие пропускается, если локальная запись
 * новее {@code event.updatedAt()} (timestamp guard). Адреса доставки игнорируются.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContactSyncService {

    private final NotificationContactRepository contactRepository;

    private final ProcessedMessageRepository processedMessageRepository;

    private final ConsumerMetrics consumerMetrics;

    @Transactional
    public void applyUserSync(@NonNull UserSyncEvent event) {
        if (event.eventId() == null || event.eventId().isBlank()) {
            log.warn("UserSyncEvent without eventId received, skipping: userId={}", event.userId());
            consumerMetrics.processed(ConsumerMetrics.CONSUMER_USER_SYNC, ConsumerMetrics.RESULT_DUPLICATE);
            return;
        }
        if (!acquireProcessingMarker(event)) {
            consumerMetrics.processed(ConsumerMetrics.CONSUMER_USER_SYNC, ConsumerMetrics.RESULT_DUPLICATE);
            return;
        }
        NotificationContact contact = contactRepository.findByUserId(event.userId()).orElse(null);
        if (isStale(contact, event)) {
            consumerMetrics.processed(ConsumerMetrics.CONSUMER_USER_SYNC, ConsumerMetrics.RESULT_DUPLICATE);
            return;
        }
        if (contact == null) {
            contact = NotificationContact.builder().userId(event.userId()).build();
        }
        contact.setEmail(event.email());
        contact.setPhone(event.phone());
        contact.setUpdatedAt(event.updatedAt());
        contactRepository.save(contact);
        consumerMetrics.processed(ConsumerMetrics.CONSUMER_USER_SYNC, ConsumerMetrics.RESULT_APPLIED);
        log.info("Contact read-model updated for userId={}, eventId={}", event.userId(), event.eventId());
    }

    /**
     * Dedup по eventId: маркер в {@code processed_messages}. Конкурентная доставка,
     * успевшая вставить маркер одновременно, трактуется как "уже обработано" (no-op).
     */
    private boolean acquireProcessingMarker(@NonNull UserSyncEvent event) {
        if (processedMessageRepository.existsById(event.eventId())) {
            log.info("Duplicate user-sync event skipped: eventId={}, userId={}",
                    event.eventId(), event.userId());
            return false;
        }
        try {
            processedMessageRepository.saveAndFlush(
                    ProcessedMessage.builder()
                            .eventId(event.eventId())
                            .processedAt(LocalDateTime.now())
                            .build());
            return true;
        } catch (DataIntegrityViolationException e) {
            log.info("Concurrent delivery of the same user-sync event detected, skipping: eventId={}, userId={}",
                    event.eventId(), event.userId());
            return false;
        }
    }

    /**
     * Timestamp guard: событие устарело, если локальная запись не старше события.
     */
    private boolean isStale(NotificationContact contact, @NonNull UserSyncEvent event) {
        boolean stale = contact != null
                && contact.getUpdatedAt() != null
                && event.updatedAt() != null
                && !contact.getUpdatedAt().isBefore(event.updatedAt());
        if (stale) {
            log.info("Skipping stale user-sync event: userId={}, localUpdatedAt={}, eventUpdatedAt={}",
                    event.userId(), contact.getUpdatedAt(), event.updatedAt());
        }
        return stale;
    }
}
