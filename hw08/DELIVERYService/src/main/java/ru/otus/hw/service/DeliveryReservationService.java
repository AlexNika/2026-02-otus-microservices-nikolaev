package ru.otus.hw.service;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import ru.otus.hw.controller.DeliveryReservation.DeliveryReservationStatusFilter;
import ru.otus.hw.dto.DeliveryReservationResponse;

import java.time.LocalDate;

/**
 * Сервис резервов доставки для admin API (read-only).
 */
public interface DeliveryReservationService {

    DeliveryReservationResponse getByOrderId(Long orderId);

    DeliveryReservationResponse getById(Long reservationId);

    Page<DeliveryReservationResponse> getReservations(@NonNull LocalDate date,
                                                      @Nullable DeliveryReservationStatusFilter status,
                                                      @Nullable Long courierSlotId,
                                                      @Nullable Integer assignedCourierNumber,
                                                      @NonNull Pageable pageable);
}
