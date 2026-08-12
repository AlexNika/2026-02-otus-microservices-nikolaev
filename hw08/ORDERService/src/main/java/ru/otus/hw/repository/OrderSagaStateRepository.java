package ru.otus.hw.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.otus.hw.models.OrderSagaState;

import java.util.Optional;

public interface OrderSagaStateRepository extends JpaRepository<OrderSagaState, Long> {

    Optional<OrderSagaState> findByOrderId(Long orderId);
}
