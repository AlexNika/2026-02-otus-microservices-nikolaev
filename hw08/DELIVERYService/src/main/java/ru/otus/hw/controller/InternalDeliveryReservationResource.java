package ru.otus.hw.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.otus.hw.dto.CancelDeliveryResponse;
import ru.otus.hw.dto.DeliveryReservationResponse;
import ru.otus.hw.dto.ReserveDeliveryRequest;
import ru.otus.hw.service.InternalDeliveryReservationService;
import ru.otus.hw.service.ReserveDeliveryResult;

@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/delivery/reservations")
public class InternalDeliveryReservationResource implements InternalDeliveryReservation {

    private final InternalDeliveryReservationService internalDeliveryReservationService;

    @Override
    public ResponseEntity<DeliveryReservationResponse> reserve(ReserveDeliveryRequest request) {
        ReserveDeliveryResult result = internalDeliveryReservationService.reserve(request);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.response());
    }

    @Override
    public ResponseEntity<DeliveryReservationResponse> getByOrderId(Long orderId) {
        return ResponseEntity.ok(internalDeliveryReservationService.getByOrderId(orderId));
    }

    @Override
    public ResponseEntity<DeliveryReservationResponse> getById(Long reservationId) {
        return ResponseEntity.ok(internalDeliveryReservationService.getById(reservationId));
    }

    @Override
    public ResponseEntity<DeliveryReservationResponse> confirm(Long orderId) {
        return ResponseEntity.ok(internalDeliveryReservationService.confirm(orderId));
    }

    @Override
    public ResponseEntity<CancelDeliveryResponse> cancel(Long orderId) {
        return ResponseEntity.ok(internalDeliveryReservationService.cancel(orderId));
    }
}
