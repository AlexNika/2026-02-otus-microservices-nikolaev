package ru.otus.hw.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import ru.otus.hw.controller.DeliveryReservation.DeliveryReservationStatusFilter;
import ru.otus.hw.dto.DeliveryReservationResponse;

import java.time.LocalDate;

/**
 * Сервис резервов доставки для, например, admin API (read-only).
 */
public interface DeliveryReservationService {

    DeliveryReservationResponse getByOrderId(Long orderId);

    DeliveryReservationResponse getById(Long reservationId);

    Page<DeliveryReservationResponse> getReservations(LocalDate date,
                                                      DeliveryReservationStatusFilter status,
                                                      Long courierSlotId,
                                                      Integer assignedCourierNumber,
                                                      Pageable pageable);
}
