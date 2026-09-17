package ru.otus.hw.repository;

import org.jspecify.annotations.NullMarked;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.otus.hw.dto.ReservationStatus;
import ru.otus.hw.model.ProductReservation;

import java.util.List;
import java.util.Optional;

public interface ProductReservationRepository extends JpaRepository<ProductReservation, Long> {

    @Override
    @NullMarked
    @EntityGraph("productReservation-product-graph")
    Optional<ProductReservation> findById(Long id);

    @Override
    @NullMarked
    @EntityGraph("productReservation-product-graph")
    Page<ProductReservation> findAll(Pageable pageable);

    @EntityGraph("productReservation-product-graph")
    List<ProductReservation> findByOrderId(Long orderId);

    @EntityGraph("productReservation-product-graph")
    List<ProductReservation> findByOrderIdAndReservationStatus(
            Long orderId, ReservationStatus reservationStatus);

    @EntityGraph("productReservation-product-graph")
    Optional<ProductReservation> findByIdempotencyKey(String idempotencyKey);

    @EntityGraph("productReservation-product-graph")
    List<ProductReservation> findByProductId(Long productId);
}
