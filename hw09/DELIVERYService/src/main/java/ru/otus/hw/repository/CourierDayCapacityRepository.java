package ru.otus.hw.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.otus.hw.model.CourierDayCapacity;

import java.time.LocalDate;
import java.util.Optional;

public interface CourierDayCapacityRepository extends JpaRepository<CourierDayCapacity, Long> {

    /**
     * Получить дневную ёмкость курьеров вместе со слотами.
     */
    @EntityGraph(
            value = "CourierDayCapacity.withSlots",
            type = EntityGraph.EntityGraphType.FETCH
    )
    Optional<CourierDayCapacity> findByCapacityDate(LocalDate capacityDate);

    /**
     * Быстрая проверка, задана ли ёмкость на дату.
     */
    boolean existsByCapacityDate(LocalDate capacityDate);

    /**
     * Получить дневную ёмкость с пессимистичной блокировкой.
     * Используется:
     * - при обновлении количества курьеров на день;
     * - при резервировании доставки, чтобы параллельно не изменили courierCount.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT cdc
            FROM CourierDayCapacity cdc
            WHERE cdc.capacityDate = :capacityDate
            """)
    Optional<CourierDayCapacity> findLockedByCapacityDate(
            @Param("capacityDate") LocalDate capacityDate
    );

    /**
     * Получить дневную ёмкость с блокировкой и сразу загрузить слоты.
     * Используется в админском API при обновлении/пересоздании слотов.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(
            value = "CourierDayCapacity.withSlots",
            type = EntityGraph.EntityGraphType.FETCH
    )
    @Query("""
            SELECT cdc
            FROM CourierDayCapacity cdc
            WHERE cdc.capacityDate = :capacityDate
            """)
    Optional<CourierDayCapacity> findLockedWithSlotsByCapacityDate(
            @Param("capacityDate") LocalDate capacityDate
    );
}