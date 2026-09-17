package ru.otus.hw.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.NamedAttributeNode;
import jakarta.persistence.NamedEntityGraph;
import jakarta.persistence.NamedEntityGraphs;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Check;
import ru.otus.hw.models.base.AuditableEntity;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(
        name = "courier_slots",
        indexes = {
                @Index(name = "idx_courier_slots_day_capacity_id", columnList = "courier_day_capacity_id"),
                @Index(name = "idx_courier_slots_start_end", columnList = "slot_start, slot_end")
        },
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_courier_slots_day_capacity_slot",
                        columnNames = {
                                "courier_day_capacity_id",
                                "slot_start",
                                "slot_end"
                        }
                )
        }
)
@NamedEntityGraphs({
        @NamedEntityGraph(
                name = "CourierSlot.withDayCapacity",
                attributeNodes = {
                        @NamedAttributeNode("dayCapacity")
                }
        ),
        @NamedEntityGraph(
                name = "CourierSlot.withReservations",
                attributeNodes = {
                        @NamedAttributeNode("reservations")
                }
        ),
        @NamedEntityGraph(
                name = "CourierSlot.withDayCapacityAndReservations",
                attributeNodes = {
                        @NamedAttributeNode("dayCapacity"),
                        @NamedAttributeNode("reservations")
                }
        )
})
@Check(constraints = "slot_start < slot_end")
public class CourierSlot extends AuditableEntity<Long> {

    /**
     * День, к которому относится слот.
     * Фактически источник лимита курьеров для этого слота.
     */
    @NotNull
    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "courier_day_capacity_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_courier_slots_day_capacity")
    )
    private CourierDayCapacity dayCapacity;

    /**
     * Начало временного слота.
     */
    @NotNull
    @Column(name = "slot_start", nullable = false)
    private LocalTime slotStart;

    /**
     * Конец временного слота.
     */
    @NotNull
    @Column(name = "slot_end", nullable = false)
    private LocalTime slotEnd;

    /**
     * Резервы доставки, которые относятся к этому слоту.
     */
    @Builder.Default
    @OneToMany(
            mappedBy = "courierSlot",
            fetch = FetchType.LAZY
    )
    private List<DeliveryReservation> reservations = new ArrayList<>();

}