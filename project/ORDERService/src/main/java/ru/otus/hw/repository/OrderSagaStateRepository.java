package ru.otus.hw.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.otus.hw.models.OrderSagaState;
import ru.otus.hw.models.OrderSagaState.SagaStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderSagaStateRepository extends JpaRepository<OrderSagaState, Long> {

    Optional<OrderSagaState> findByOrderId(Long orderId);

    /**
     * Саги в промежуточных статусах без прогресса дольше порога stale-after - кандидаты
     * на recovery. JOIN FETCH заказа, чтобы recovery работал вне открытой транзакции.
     */
    @Query("""
            SELECT s FROM OrderSagaState s JOIN FETCH s.order
            WHERE s.sagaStatus IN :statuses
              AND s.updated < :staleBefore
            """)
    List<OrderSagaState> findStaleSagas(@Param("statuses") List<SagaStatus> statuses,
                                        @Param("staleBefore") LocalDateTime staleBefore);
}
