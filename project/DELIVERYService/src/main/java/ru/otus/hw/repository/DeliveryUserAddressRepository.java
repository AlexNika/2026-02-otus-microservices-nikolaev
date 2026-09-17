package ru.otus.hw.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.otus.hw.model.DeliveryUserAddress;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface DeliveryUserAddressRepository extends JpaRepository<DeliveryUserAddress, Long> {

    List<DeliveryUserAddress> findAllByUserId(Long userId);

    Optional<DeliveryUserAddress> findByUserIdAndSourceAddressId(Long userId, Long sourceAddressId);

    void deleteAllByUserId(Long userId);

    void deleteAllByUserIdAndSourceAddressIdNotIn(Long userId, List<Long> sourceAddressIds);

    /**
     * Максимальное updatedAt записей пользователя (время последнего применённого события) -
     * timestamp guard против out-of-order доставки.
     */
    @Query("SELECT MAX(d.updatedAt) FROM DeliveryUserAddress d WHERE d.userId = :userId")
    Optional<Instant> findMaxUpdatedAtByUserId(@Param("userId") Long userId);
}
