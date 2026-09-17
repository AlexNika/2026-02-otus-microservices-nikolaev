package ru.otus.hw.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.otus.hw.model.CourierDayCapacity;
import ru.otus.hw.model.CourierSlot;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

public interface CourierSlotRepository extends JpaRepository<CourierSlot, Long> {

    /**
     * Поиск слота по дневной ёмкости и временному интервалу.
     */
    @EntityGraph(
            value = "CourierSlot.withDayCapacity",
            type = EntityGraph.EntityGraphType.FETCH
    )
    Optional<CourierSlot> findByDayCapacityAndSlotStartAndSlotEnd(
            CourierDayCapacity dayCapacity,
            LocalTime slotStart,
            LocalTime slotEnd
    );

    /**
     * Поиск слота сразу по дате и временному интервалу.
     */
    @EntityGraph(
            value = "CourierSlot.withDayCapacity",
            type = EntityGraph.EntityGraphType.FETCH
    )
    @Query("""
        SELECT s
        FROM CourierSlot s
        JOIN FETCH s.dayCapacity dc
        WHERE dc.capacityDate = :date
          AND s.slotStart = :slotStart
          AND s.slotEnd = :slotEnd
        """)
    Optional<CourierSlot> findByDateAndTimeSlot(
            @Param("date") LocalDate date,
            @Param("slotStart") LocalTime slotStart,
            @Param("slotEnd") LocalTime slotEnd
    );

    /**
     * Быстрая проверка существования слота.
     */
    boolean existsByDayCapacityAndSlotStartAndSlotEnd(
            CourierDayCapacity dayCapacity,
            LocalTime slotStart,
            LocalTime slotEnd
    );

    /**
     * Получить все слоты конкретного дня, отсортированные по времени начала.
     */
    @EntityGraph(
            value = "CourierSlot.withDayCapacity",
            type = EntityGraph.EntityGraphType.FETCH
    )
    List<CourierSlot> findByDayCapacityOrderBySlotStartAsc(CourierDayCapacity dayCapacity);

    /**
     * Получить все слоты по дате, отсортированные по времени начала.
     */
    @EntityGraph(
            value = "CourierSlot.withDayCapacity",
            type = EntityGraph.EntityGraphType.FETCH
    )
    @Query("""
            SELECT s
            FROM CourierSlot s
            WHERE s.dayCapacity.capacityDate = :capacityDate
            ORDER BY s.slotStart ASC
            """)
    List<CourierSlot> findByCapacityDateOrderBySlotStartAsc(
            @Param("capacityDate") LocalDate capacityDate
    );

    /**
     * Получить все слоты по id дневной ёмкости.
     */
    @EntityGraph(
            value = "CourierSlot.withDayCapacity",
            type = EntityGraph.EntityGraphType.FETCH
    )
    List<CourierSlot> findByDayCapacityIdOrderBySlotStartAsc(Long dayCapacityId);

    /**
     * Заблокировать слот по id.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT s
            FROM CourierSlot s
            WHERE s.id = :slotId
            """)
    Optional<CourierSlot> findLockedById(
            @Param("slotId") Long slotId
    );

    /**
     * Заблокировать слот по id и сразу подтянуть дневную ёмкость.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT s
            FROM CourierSlot s
            JOIN FETCH s.dayCapacity dc
            WHERE s.id = :slotId
            """)
    Optional<CourierSlot> findLockedWithDayCapacityById(
            @Param("slotId") Long slotId
    );

    /**
     * Заблокировать слот по дате и временному интервалу.
     * Этот метод удобно использовать при резервировании доставки.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT s
            FROM CourierSlot s
            JOIN FETCH s.dayCapacity dc
            WHERE dc.capacityDate = :capacityDate
              AND s.slotStart = :slotStart
              AND s.slotEnd = :slotEnd
            """)
    Optional<CourierSlot> findLockedByDateAndTimeSlot(
            @Param("capacityDate") LocalDate capacityDate,
            @Param("slotStart") LocalTime slotStart,
            @Param("slotEnd") LocalTime slotEnd
    );

}