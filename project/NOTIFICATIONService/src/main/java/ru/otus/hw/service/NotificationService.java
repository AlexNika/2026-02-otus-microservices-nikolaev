package ru.otus.hw.service;

import ru.otus.hw.dto.NotificationDto;
import ru.otus.hw.dto.NotificationEvent;

import java.util.List;

public interface NotificationService {

    void saveNotification(NotificationEvent event);

    List<NotificationDto> getNotificationsByUserId(Long userId);

}
