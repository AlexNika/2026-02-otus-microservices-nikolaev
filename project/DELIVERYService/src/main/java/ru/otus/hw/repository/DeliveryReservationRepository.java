package ru.otus.hw.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.otus.hw.model.CourierSlot;
import ru.otus.hw.model.DeliveryReservation;
import ru.otus.hw.model.DeliveryReservation.DeliveryReservationStatus;
import ru.otus.hw.repository.projection.SlotActiveCountProjection;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DeliveryReservationRepository extends JpaRepository<DeliveryReservation, Long> {

    /**
     * Получить резерв по orderId вместе со слотом и дневной ёмкостью.
     */
    @EntityGraph(
            value = "DeliveryReservation.withSlotAndDayCapacity",
            type = EntityGraph.EntityGraphType.FETCH
    )
    Optional<DeliveryReservation> findByOrderId(Long orderId);

    /**
     * Получить резерв по orderId с пессимистичной блокировкой.
     * Используется при confirm/cancel, чтобы избежать параллельных изменений.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(
            value = "DeliveryReservation.withSlotAndDayCapacity",
            type = EntityGraph.EntityGraphType.FETCH
    )
    @Query("""
            SELECT dr
            FROM DeliveryReservation dr
            WHERE dr.orderId = :orderId
            """)
    Optional<DeliveryReservation> findLockedByOrderId(
            @Param("orderId") Long orderId
    );

    /**
     * Найти резерв по orderId и списку статусов.
     * Например, можно искать только активные резервы:
     * RESERVED, CONFIRMED.
     */
    Optional<DeliveryReservation> findByOrderIdAndStatusIn(
            Long orderId,
            Collection<DeliveryReservationStatus> statuses
    );

    /**
     * Быстрая проверка, существует ли резерв для заказа.
     */
    boolean existsByOrderId(Long orderId);

    /**
     * Количество резервов в слоте по списку статусов.
     * Для активных резервов вызывать со статусами:
     * RESERVED, CONFIRMED.
     */
    Long countByCourierSlotIdAndStatusIn(
            Long courierSlotId,
            Collection<DeliveryReservationStatus> statuses
    );

    /**
     * То же самое, но через entity CourierSlot.
     */
    Long countByCourierSlotAndStatusIn(
            CourierSlot courierSlot,
            Collection<DeliveryReservationStatus> statuses
    );

    /**
     * Получить занятые номера курьеров в конкретном слоте.
     * Используется для выбора первого свободного assignedCourierNumber.
     */
    @Query("""
            SELECT DISTINCT dr.assignedCourierNumber
            FROM DeliveryReservation dr
            WHERE dr.courierSlot.id = :courierSlotId
              AND dr.status IN :statuses
            ORDER BY dr.assignedCourierNumber ASC
            """)
    List<Integer> findActiveAssignedCourierNumbersByCourierSlotIdAndStatusIn(
            @Param("courierSlotId") Long courierSlotId,
            @Param("statuses") Collection<DeliveryReservationStatus> statuses
    );

    /**
     * Проверка, занят ли конкретный номер курьера в конкретном слоте.
     * Используется как дополнительная проверка перед вставкой.
     */
    boolean existsByCourierSlotIdAndAssignedCourierNumberAndStatusIn(
            Long courierSlotId,
            Integer assignedCourierNumber,
            Collection<DeliveryReservationStatus> statuses
    );

    /**
     * Получить активные резервы слота.
     */
    @EntityGraph(
            value = "DeliveryReservation.withSlotAndDayCapacity",
            type = EntityGraph.EntityGraphType.FETCH
    )
    @Query("""
            SELECT dr
            FROM DeliveryReservation dr
            WHERE dr.courierSlot.id = :courierSlotId
              AND dr.status IN :statuses
            """)
    List<DeliveryReservation> findActiveByCourierSlotIdAndStatusIn(
            @Param("courierSlotId") Long courierSlotId,
            @Param("statuses") Collection<DeliveryReservationStatus> statuses
    );

    /**
     * Групповой подсчёт активных резервов по слотам для конкретной даты.
     * Удобно использовать в GET /courier-capacity/{date},
     * чтобы посчитать reservedCount для каждого слота без N+1.
     */
    @Query("""
            SELECT dr.courierSlot.id AS slotId,
                   count(dr) AS activeCount
            FROM DeliveryReservation dr
            WHERE dr.courierSlot.dayCapacity.capacityDate = :capacityDate
              AND dr.status IN :statuses
            GROUP BY dr.courierSlot.id
            """)
    List<SlotActiveCountProjection> countActiveByCapacityDateAndStatuses(
            @Param("capacityDate") LocalDate capacityDate,
            @Param("statuses") Collection<DeliveryReservationStatus> statuses
    );

    /**
     * Групповой подсчёт активных резервов по слотам для дневной ёмкости.
     * Удобно при обновлении courierCount:
     * можно проверить, что новый лимит не меньше уже активных резервов.
     */
    @Query("""
            SELECT dr.courierSlot.id AS slotId,
                   count(dr) AS activeCount
            FROM DeliveryReservation dr
            WHERE dr.courierSlot.dayCapacity.id = :dayCapacityId
              AND dr.status IN :statuses
            GROUP BY dr.courierSlot.id
            """)
    List<SlotActiveCountProjection> countActiveByDayCapacityIdAndStatuses(
            @Param("dayCapacityId") Long dayCapacityId,
            @Param("statuses") Collection<DeliveryReservationStatus> statuses
    );

    /**
     * Общее количество активных резервов на конкретную дату.
     * Может быть полезно для админских отчётов или отладки.
     */
    @Query("""
            SELECT count(dr)
            FROM DeliveryReservation dr
            WHERE dr.courierSlot.dayCapacity.capacityDate = :capacityDate
              AND dr.status IN :statuses
            """)
    long countActiveByCapacityDateAndStatusIn(
            @Param("capacityDate") LocalDate capacityDate,
            @Param("statuses") Collection<DeliveryReservationStatus> statuses
    );

    /**
     * Пагинируемый фильтр резервов для admin GET /reservations.
     * Все фильтры, кроме date, опциональны.
     */
    @EntityGraph(value = "DeliveryReservation.withSlotAndDayCapacity", type = EntityGraph.EntityGraphType.FETCH)
    @Query("""
            SELECT dr FROM DeliveryReservation dr
            WHERE dr.courierSlot.dayCapacity.capacityDate = :date
              AND (:status IS NULL OR dr.status = :status)
              AND (:courierSlotId IS NULL OR dr.courierSlot.id = :courierSlotId)
              AND (:assignedCourierNumber IS NULL OR dr.assignedCourierNumber = :assignedCourierNumber)
            """)
    Page<DeliveryReservation> findWithFilters(@Param("date") LocalDate date,
                                              @Param("status") DeliveryReservationStatus status,
                                              @Param("courierSlotId") Long courierSlotId,
                                              @Param("assignedCourierNumber") Integer assignedCourierNumber,
                                              Pageable pageable);
}