package ru.otus.hw.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import ru.otus.hw.models.base.AuditableEntity;

@Getter
@Setter
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "notifications", indexes = {
        @Index(name = "idx_user_id_order_id", columnList = "user_id, order_id")
})
public class Notification extends AuditableEntity<Long> {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "message", nullable = false, length = 1024)
    private String message;

    /**
     * Уведомления заказа несут orderId; уведомления о жизненном цикле регистрации
     * (USER_CREATED / ACCOUNT_CREATED / ...) приходят без заказа (null).
     */
    @Column(name = "order_id")
    private Long orderId;

    /**
     * Статус источника как есть: SUCCESS/FAILED (заказы, PLACED маппится в SUCCESS),
     * либо статус жизненного цикла регистрации (USER_CREATED, USER_CREATION_FAILED,
     * ACCOUNT_CREATED, ACCOUNT_CREATION_FAILED, ACCOUNT_ACTIVATED).
     */
    @Column(name = "notification_status", nullable = false, length = 32)
    private String notificationStatus;
}
