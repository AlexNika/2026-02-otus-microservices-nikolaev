package ru.otus.hw.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.otus.hw.dto.DeliveryReservationResponse;
import ru.otus.hw.service.DeliveryReservationService;

import java.time.LocalDate;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/delivery")
public class DeliveryReservationResource implements DeliveryReservation {

    private final DeliveryReservationService deliveryReservationService;

    @Override
    public ResponseEntity<DeliveryReservationResponse> getByOrderId(Long orderId) {
        return ResponseEntity.ok(deliveryReservationService.getByOrderId(orderId));
    }

    @Override
    public ResponseEntity<DeliveryReservationResponse> getById(Long reservationId) {
        return ResponseEntity.ok(deliveryReservationService.getById(reservationId));
    }

    @Override
    public ResponseEntity<Page<DeliveryReservationResponse>> getReservations(LocalDate date,
                                                                             DeliveryReservationStatusFilter status,
                                                                             Long courierSlotId,
                                                                             Integer assignedCourierNumber,
                                                                             Pageable pageable) {
        return ResponseEntity.ok(deliveryReservationService
                .getReservations(date, status, courierSlotId, assignedCourierNumber, pageable));
    }
}
