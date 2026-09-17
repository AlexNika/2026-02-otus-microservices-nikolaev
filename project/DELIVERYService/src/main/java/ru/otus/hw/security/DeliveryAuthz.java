package ru.otus.hw.security;

import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import ru.otus.hw.repository.DeliveryReservationRepository;

/**
 * Ownership-проверки DELIVERYService для {@code @PreAuthorize}-выражений
 * (доступен в спелах как {@code @deliveryAuthz}). Владелец брони определяется
 * по локальным данным ({@code delivery_reservations.user_id}), без межсервисных вызовов.
 */
@Component("deliveryAuthz")
@RequiredArgsConstructor
public class DeliveryAuthz {

    private final DeliveryReservationRepository deliveryReservationRepository;

    private final OwnershipChecker ownershipChecker;

    /**
     * Текущий пользователь - владелец брони по {@code orderId} или ADMIN.
     * Для брони без владельца (исторической) доступ только у ADMIN.
     */
    public boolean reservationOwnerByOrder(@NonNull Long orderId) {
        return deliveryReservationRepository.findByOrderId(orderId)
                .map(reservation -> ownershipChecker.ownerOrAdmin(reservation.getUserId()))
                .orElse(false);
    }

    /**
     * Текущий пользователь - владелец брони по {@code reservationId} или ADMIN.
     */
    public boolean reservationOwnerById(@NonNull Long reservationId) {
        return deliveryReservationRepository.findById(reservationId)
                .map(reservation -> ownershipChecker.ownerOrAdmin(reservation.getUserId()))
                .orElse(false);
    }
}
