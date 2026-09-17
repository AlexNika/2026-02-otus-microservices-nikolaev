package ru.otus.hw.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import ru.otus.hw.models.base.AuditableEntity;

import java.time.Instant;

/**
 * Read-модель контактов пользователя, реплицируемая из USERService событием UserSyncEvent
 * (канал USER → NOTIFICATION, user.sync.events / user.profile.sync).
 *
 * <p>UNIQUE по {@code user_id}: гарантия отношения 1:1 и идемпотентности upsert при повторной
 * доставке. Поля {@code email}/{@code phone} обновляются; адреса доставки игнорируются
 * (это зона ответственности DELIVERYService).
 *
 * <p>{@code updatedAt} - время события-источника (timestamp guard против out-of-order
 * доставки); технические аудита-колонки наследуются от {@link AuditableEntity}.
 */
@Getter
@Setter
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "notification_contacts",
        uniqueConstraints = @UniqueConstraint(name = "uk_notification_contacts_user_id",
                columnNames = "user_id"))
public class NotificationContact extends AuditableEntity<Long> {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "phone", length = 20)
    private String phone;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
