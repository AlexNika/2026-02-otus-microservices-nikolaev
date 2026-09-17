package ru.otus.hw.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.otus.hw.model.NotificationContact;

import java.util.Optional;

public interface NotificationContactRepository extends JpaRepository<NotificationContact, Long> {

    Optional<NotificationContact> findByUserId(Long userId);
}
