package ru.otus.hw.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.otus.hw.config.properties.OutboxProperties;
import ru.otus.hw.models.OutboxEvent.OutboxStatus;
import ru.otus.hw.repository.OutboxEventRepository;

import java.time.LocalDateTime;

/**
 * Периодическая очистка {@code user_outbox}: удаляет только доставленные и подтверждённые
 * брокером (SENT) события старше периода хранения (по умолчанию 7 дней от {@code sent_at}).
 *
 * <p>Почему только SENT: события доставлены, а confirm/return-колбэки приходят за секунды,
 * поэтому возврат строки в работу спустя период хранения физически невозможен; а если строка
 * уже удалена, колбэки просто делают no-op ({@code findByEventId(...).ifPresent}).
 *
 * <p>NEW не трогаются никогда - их читает исключительно {@code OutboxPublisher}.
 * FAILED не удаляются намеренно: хранятся для ручного разбирательства.
 * Никаких изменений схемы БД: существующего индекса {@code idx_user_outbox_status}
 * достаточно для предиката {@code status = 'SENT' AND sent_at < ?}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxEventCleanupService {

    private final OutboxEventRepository outboxEventRepository;

    private final OutboxProperties outboxProperties;

    @Scheduled(fixedDelayString = "${app.outbox.cleanup-interval:PT1H}")
    @Transactional
    public void cleanupSentEvents() {
        LocalDateTime cutoff = LocalDateTime.now().minus(outboxProperties.getRetention());
        long deleted = outboxEventRepository.deleteByStatusAndSentAtBefore(OutboxStatus.SENT, cutoff);
        if (deleted > 0) {
            log.info("Removed {} expired SENT user outbox events", deleted);
        } else {
            log.debug("No expired SENT user outbox events to remove");
        }
    }
}
