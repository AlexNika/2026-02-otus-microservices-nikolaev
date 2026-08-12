package ru.otus.hw.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.NamedAttributeNode;
import jakarta.persistence.NamedEntityGraph;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import ru.otus.hw.dto.ReservationStatus;
import ru.otus.hw.models.base.AuditableEntity;

@Getter
@Setter
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(
        name = "product_reservations",
        indexes = {
            @Index(name = "idx_product_reservation_order_id", columnList = "order_id"),
            @Index(name = "idx_product_reservation_product_id", columnList = "product_id")
        },
        uniqueConstraints = {
            @UniqueConstraint(columnNames = {"order_id", "product_id"})
        }
)
@NamedEntityGraph(
        name = "productReservation-product-graph",
        attributeNodes = @NamedAttributeNode("product")
)
public class ProductReservation extends AuditableEntity<Long> {

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Min(0)
    @Builder.Default
    @Column(name = "quantity")
    private Integer quantity = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ReservationStatus reservationStatus;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private Long idempotencyKey;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
        name = "product_id",
        foreignKey = @ForeignKey(name = "fk_product_reservation_product"),
        nullable = false
    )
    private Product product;

}
