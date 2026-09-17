package ru.otus.hw.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import ru.otus.hw.dto.NotificationDto;
import ru.otus.hw.dto.NotificationEvent;

import java.util.List;

public interface NotificationService {

    void saveNotification(NotificationEvent event);

    List<NotificationDto> getNotificationsByUserId(Long userId);

    /**
     * Постраничный список уведомлений всех пользователей (представление ADMIN);
     * порядок следования задаётся {@link Pageable}.
     */
    Page<NotificationDto> getAllNotifications(Pageable pageable);

}
