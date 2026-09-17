package ru.otus.hw.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import ru.otus.hw.models.base.AuditableEntity;

/**
 * Состояние саги создания заказа. Одна строка на заказ (1:1 по уникальному order_id).
 * Хранит текущий статус саги и информацию об отказе, если он произошёл.
 */
@Getter
@Setter
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "order_saga_state")
public class OrderSagaState extends AuditableEntity<Long> {

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "order_id",
            nullable = false,
            unique = true,
            foreignKey = @ForeignKey(name = "fk_order_saga_state_order")
    )
    private Order order;

    @Enumerated(EnumType.STRING)
    @Column(name = "saga_status", nullable = false, length = 30)
    private SagaStatus sagaStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_step", length = 30)
    private SagaStep failureStep;

    @Column(name = "failure_reason", length = 2048)
    private String failureReason;

    public enum SagaStatus {
        STARTED,
        BILLING_RESERVED,
        WAREHOUSE_RESERVED,
        DELIVERY_RESERVED,
        CONFIRMED,
        COMPENSATING,
        COMPENSATED,
        COMPENSATION_FAILED
    }

    public enum SagaStep {
        BILLING_WITHDRAW,
        WAREHOUSE_RESERVE,
        DELIVERY_RESERVE,
        WAREHOUSE_CONFIRM,
        DELIVERY_CONFIRM,
        BILLING_REFUND,
        WAREHOUSE_CANCEL,
        DELIVERY_CANCEL
    }
}
