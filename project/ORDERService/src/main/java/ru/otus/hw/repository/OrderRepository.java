package ru.otus.hw.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.otus.hw.models.Order;

import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    List<Order> findByUserId(Long userId);

    List<Order> findByOrderStatus(Order.OrderStatus orderStatus);

    List<Order> findByUserIdAndOrderStatus(Long userId, Order.OrderStatus orderStatus);

    @Query("SELECT o FROM Order o WHERE o.userId = :userId AND o.orderStatus IN :statuses")
    List<Order> findActiveByUserId(@Param("userId") Long userId, @Param("statuses") List<Order.OrderStatus> statuses);

    List<Order> findByUserIdOrderByCreatedDesc(Long userId);

    Optional<Order> findTopByUserIdOrderByCreatedDesc(Long userId);

    Page<Order> findAllByUserId(Long userId, Pageable pageable);
}