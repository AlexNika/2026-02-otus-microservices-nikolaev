package ru.otus.hw.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.otus.hw.models.OutboxEvent;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    List<OutboxEvent> findByStatusOrderByCreatedAtAsc(OutboxEvent.OutboxStatus status);

    Optional<OutboxEvent> findByEventId(String eventId);

    boolean existsByEventId(String eventId);

    long deleteByStatusAndSentAtBefore(OutboxEvent.OutboxStatus status, LocalDateTime cutoff);
}
