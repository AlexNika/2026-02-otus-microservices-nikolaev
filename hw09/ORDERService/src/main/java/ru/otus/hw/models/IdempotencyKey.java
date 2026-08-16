package ru.otus.hw.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import ru.otus.hw.models.OrderSagaState.SagaStatus;
import ru.otus.hw.models.base.AuditableEntity;

import java.time.LocalDateTime;

/**
 * Ключ идемпотентности запроса создания заказа (заголовок Idempotency-Key).
 *
 * <p>Строка создаётся в одной транзакции с первым сохранением заказа, поэтому повтор
 * ключа никогда не создаёт второй заказ. Ответ сохраняется только при успехе саги
 * (response_status=201 + JSON заказа); при провале остаётся saga_status, и повтор
 * возвращает текущее состояние заказа.
 */
@Getter
@Setter
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "idempotency_keys")
public class IdempotencyKey extends AuditableEntity<Long> {

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 64)
    private String idempotencyKey;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_body", columnDefinition = "TEXT")
    private String responseBody;

    @Enumerated(EnumType.STRING)
    @Column(name = "saga_status", length = 30)
    private SagaStatus sagaStatus;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;
}
