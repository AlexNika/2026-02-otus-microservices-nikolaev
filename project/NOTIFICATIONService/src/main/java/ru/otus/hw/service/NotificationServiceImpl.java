package ru.otus.hw.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.otus.hw.dto.NotificationDto;
import ru.otus.hw.dto.NotificationEvent;
import ru.otus.hw.dto.mapper.NotificationMapper;
import ru.otus.hw.metrics.ConsumerMetrics;
import ru.otus.hw.model.Notification;
import ru.otus.hw.model.ProcessedMessage;
import ru.otus.hw.repository.NotificationRepository;
import ru.otus.hw.repository.ProcessedMessageRepository;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private final NotificationRepository notificationRepository;

    private final ProcessedMessageRepository processedMessageRepository;

    private final NotificationMapper notificationMapper;

    private final ConsumerMetrics consumerMetrics;

    /**
     * Сохраняет уведомление идемпотентно по {@code eventId}. В одной транзакции фиксируются
     * маркер обработанного сообщения ({@code processed_messages}) и само уведомление, поэтому
     * повторная доставка того же события не создаёт дубль. Конкурентная доставка, успевшая
     * вставить маркер одновременно, приводит к нарушению уникального PK и трактуется как
     * "уже обработано" (no-op), а не как ошибка.
     *
     * <p>События без {@code eventId} (старые сообщения) идут legacy-путём без дедупликации.
     */
    @Override
    @Transactional
    public void saveNotification(@NonNull NotificationEvent event) {
        String eventId = event.eventId();
        if (eventId == null || eventId.isBlank()) {
            log.info("Saving legacy notification without eventId for userId={}, orderId={}",
                    event.userId(), event.orderId());
            persistNotification(event);
            consumerMetrics.processed(ConsumerMetrics.CONSUMER_NOTIFICATION, ConsumerMetrics.RESULT_APPLIED);
            return;
        }
        if (processedMessageRepository.existsById(eventId)) {
            log.info("Duplicate notification event skipped: eventId={}, orderId={}", eventId, event.orderId());
            consumerMetrics.processed(ConsumerMetrics.CONSUMER_NOTIFICATION, ConsumerMetrics.RESULT_DUPLICATE);
            return;
        }
        try {
            processedMessageRepository.saveAndFlush(
                    ProcessedMessage.builder()
                            .eventId(eventId)
                            .processedAt(LocalDateTime.now())
                            .build());
        } catch (DataIntegrityViolationException e) {
            log.info("Concurrent delivery of the same event detected, skipping: eventId={}, orderId={}",
                    eventId, event.orderId());
            consumerMetrics.processed(ConsumerMetrics.CONSUMER_NOTIFICATION, ConsumerMetrics.RESULT_DUPLICATE);
            return;
        }
        log.info("Saving notification for userId={}, orderId={}, eventId={}", event.userId(), event.orderId(), eventId);
        persistNotification(event);
        consumerMetrics.processed(ConsumerMetrics.CONSUMER_NOTIFICATION, ConsumerMetrics.RESULT_APPLIED);
        log.info("Notification saved successfully for userId={}, orderId={}", event.userId(), event.orderId());
    }

    private void persistNotification(@NonNull NotificationEvent event) {
        Notification notification = notificationMapper.toEntity(event);
        notificationRepository.save(notification);
    }

    @Override
    @Transactional(readOnly = true)
    public List<NotificationDto> getNotificationsByUserId(Long userId) {
        log.info("Fetching notifications for userId={}", userId);
        List<Notification> notifications = notificationRepository.findByUserIdOrderByCreatedDesc(userId);
        log.info("Found {} notifications for userId={}", notifications.size(), userId);
        return notificationMapper.toDtoList(notifications);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<NotificationDto> getAllNotifications(@NonNull Pageable pageable) {
        log.info("Fetching all notifications, page={}, size={}, sort={}",
                pageable.getPageNumber(), pageable.getPageSize(), pageable.getSort());
        Page<Notification> notifications = notificationRepository.findAll(pageable);
        log.info("Found {} notifications of {} total", notifications.getNumberOfElements(),
                notifications.getTotalElements());
        return notificationMapper.toDtoPage(notifications);
    }
}
