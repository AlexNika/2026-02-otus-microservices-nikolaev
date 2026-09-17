package ru.otus.hw.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import ru.otus.hw.models.base.AuditableEntity;

import java.time.LocalDateTime;

/**
 * Запись transactional outbox AuthService: единственный тип события -
 * расширенный {@code UserCreatedEvent} (credentials сохранены → потребители
 * создают проекции). Записывается в одной транзакции с регистрацией,
 * публикуется scheduled-вычиткой ({@code OutboxPublisher}).
 *
 * <p>{@code eventId} совпадает с eventId события: UNIQUE-ограничение делает
 * повторную запись невозможной, а идемпотентность потребления по natural key
 * делает безопасными повторные публикации при retry.
 */
@Getter
@Setter
@Builder
@Entity
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "auth_outbox", indexes = {
        @Index(name = "idx_auth_outbox_status", columnList = "status")
})
public class OutboxEvent extends AuditableEntity<Long> {

    @Column(name = "event_id", nullable = false, unique = true, length = 36)
    private String eventId;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private OutboxStatus status;

    @Column(name = "attempts", nullable = false)
    private Integer attempts;

    @Column(name = "traceparent", length = 55)
    private String traceparent;

    @Column(name = "tracestate", columnDefinition = "TEXT")
    private String tracestate;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    public enum OutboxStatus {
        NEW,
        SENT,
        FAILED
    }
}
