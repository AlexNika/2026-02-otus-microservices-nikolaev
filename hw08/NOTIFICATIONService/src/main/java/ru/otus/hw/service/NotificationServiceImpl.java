package ru.otus.hw.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.otus.hw.dto.NotificationDto;
import ru.otus.hw.dto.NotificationEvent;
import ru.otus.hw.dto.mapper.NotificationMapper;
import ru.otus.hw.model.Notification;
import ru.otus.hw.repository.NotificationRepository;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private final NotificationRepository notificationRepository;

    private final NotificationMapper notificationMapper;

    @Override
    @Transactional
    public void saveNotification(@NonNull NotificationEvent event) {
        log.info("Saving notification for userId={}, orderId={}", event.userId(), event.orderId());
        Notification notification = notificationMapper.toEntity(event);
        notificationRepository.save(notification);
        log.info("Notification saved successfully for userId={}, orderId={}", event.userId(), event.orderId());
    }

    @Override
    @Transactional(readOnly = true)
    public List<NotificationDto> getNotificationsByUserId(Long userId) {
        log.info("Fetching notifications for userId={}", userId);
        List<Notification> notifications = notificationRepository.findByUserIdOrderByCreatedDesc(userId);
        log.info("Found {} notifications for userId={}", notifications.size(), userId);
        return notificationMapper.toDtoList(notifications);
    }
}
