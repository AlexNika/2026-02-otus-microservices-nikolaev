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
import jakarta.persistence.NamedEntityGraphs;
import jakarta.persistence.NamedSubgraph;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Check;
import ru.otus.hw.models.base.AuditableEntity;

import java.util.List;

@Getter
@Setter
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(
        name = "delivery_reservations",
        indexes = {
                @Index(name = "idx_delivery_reservations_courier_slot_id", columnList = "courier_slot_id"),
                @Index(name = "idx_delivery_reservations_status", columnList = "status")
        },
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_delivery_reservations_order_id",
                        columnNames = "order_id"
                )
        }
)
@NamedEntityGraphs({
        @NamedEntityGraph(
                name = "DeliveryReservation.withSlot",
                attributeNodes = {
                        @NamedAttributeNode("courierSlot")
                }
        ),
        @NamedEntityGraph(
                name = "DeliveryReservation.withSlotAndDayCapacity",
                attributeNodes = {
                        @NamedAttributeNode(value = "courierSlot", subgraph = "courierSlotGraph")
                },
                subgraphs = {
                        @NamedSubgraph(
                                name = "courierSlotGraph",
                                attributeNodes = {
                                        @NamedAttributeNode("dayCapacity")
                                }
                        )
                }
        )
})
@Check(constraints = "assigned_courier_number > 0 AND status IN ('RESERVED', 'CONFIRMED', 'CANCELLED', 'FAILED')")
public class DeliveryReservation extends AuditableEntity<Long> {

    /**
     * Активные статусы резерва: RESERVED и CONFIRMED.
     * Используются при подсчёте занятости курьеров и слотов.
     */
    public static final List<DeliveryReservationStatus> ACTIVE_STATUSES = List.of(
            DeliveryReservationStatus.RESERVED,
            DeliveryReservationStatus.CONFIRMED
    );

    /**
     * Идентификатор заказа.
     * Для одного заказа может быть только один активный/исторический delivery reservation.
     */
    @NotNull
    @Column(name = "order_id", nullable = false)
    private Long orderId;

    /**
     * Слот доставки, в котором сделан резерв.
     */
    @NotNull
    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "courier_slot_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_delivery_reservations_courier_slot")
    )
    private CourierSlot courierSlot;

    /**
     * Номер курьера.
     * В текущей упрощённой модели это синтетический номер от 1 до courierCount из CourierDayCapacity.
     * Он не ссылается на отдельную сущность Courier.
     * Если позже появится реальный курьер, это поле можно заменить на ссылку на Courier/CourierWorkday.
     */
    @NotNull
    @Min(value = 1, message = "Assigned courier number must be greater than or equal to 1")
    @Column(name = "assigned_courier_number", nullable = false)
    private Integer assignedCourierNumber;

    /**
     * Статус резерва доставки.
     */
    @NotNull
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DeliveryReservationStatus status = DeliveryReservationStatus.RESERVED;

    public enum DeliveryReservationStatus {
        RESERVED,
        CONFIRMED,
        CANCELLED,
        FAILED
    }
}