package ru.otus.hw.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Запись transactional outbox. Общий outbox несёт события нескольких типов
 * (см. {@link EventType}); записывается в одной транзакции с бизнес-изменением,
 * публикуется scheduled-вычиткой ({@code OutboxPublisher}).
 *
 * <p>{@code eventId} совпадает с eventId самого события: UNIQUE-ограничение делает
 * повторную запись невозможной, а идемпотентность потребления по natural key делает
 * безопасными повторные публикации при retry.
 */
@Getter
@Setter
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "user_outbox", indexes = {
        @Index(name = "idx_user_outbox_status", columnList = "status")
})
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true, length = 36)
    private String eventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 20)
    @Builder.Default
    private EventType eventType = EventType.USER_CREATED;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private OutboxStatus status;

    @Column(name = "attempts", nullable = false)
    private Integer attempts;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "traceparent", length = 55)
    private String traceparent;

    @Column(name = "tracestate", columnDefinition = "TEXT")
    private String tracestate;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    /**
     * Тип события в общем outbox: определяет exchange/routing key и класс десериализации payload.
     */
    public enum EventType {
        /**
         * USER → BILLING: пользователь зарегистрирован (users.events / user.created).
         */
        USER_CREATED,

        /**
         * USER → NOTIFICATION, DELIVERY: полный снимок контактов и адресов
         * (user.sync.events / user.profile.sync).
         */
        USER_SYNC
    }

    public enum OutboxStatus {
        NEW,
        SENT,
        FAILED
    }
}
