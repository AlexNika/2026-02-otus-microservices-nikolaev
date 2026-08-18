package ru.otus.hw.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.NamedAttributeNode;
import jakarta.persistence.NamedEntityGraph;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import ru.otus.hw.models.base.AuditableEntity;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(
        name = "courier_day_capacities",
        indexes = {
                @Index(name = "idx_courier_day_capacities_date", columnList = "capacity_date")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_courier_day_capacities_date", columnNames = "capacity_date")
        }
)
@NamedEntityGraph(
        name = "CourierDayCapacity.withSlots",
        attributeNodes = {
                @NamedAttributeNode("slots")
        }
)
public class CourierDayCapacity extends AuditableEntity<Long> {

    /**
     * Дата, на которую задано количество курьеров.
     * Храним отдельно как capacity_date, чтобы не использовать зарезервированное имя date.
     */
    @NotNull
    @Column(name = "capacity_date", nullable = false)
    private LocalDate capacityDate;

    /**
     * Общее количество курьеров, доступных в этот день.
     * Если бизнес разрешает "отключать" день - @Min(0).
     * Если день всегда должен иметь хотя бы одного курьера - @Min(1).
     */
    @NotNull
    @Min(value = 0, message = "Courier count must be greater than or equal to 0")
    @Builder.Default
    @Column(name = "courier_count", nullable = false)
    private Integer courierCount = 0;

    /**
     * Слоты, которые относятся к этому дню.
     */
    @Builder.Default
    @OneToMany(
            mappedBy = "dayCapacity",
            fetch = FetchType.LAZY
    )
    private List<CourierSlot> slots = new ArrayList<>();

}