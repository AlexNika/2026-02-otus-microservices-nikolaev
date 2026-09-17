package ru.otus.hw.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.otus.hw.controller.DeliveryReservation.DeliveryReservationStatusFilter;
import ru.otus.hw.dto.DeliveryReservationResponse;
import ru.otus.hw.dto.mapper.DeliveryReservationMapper;
import ru.otus.hw.exception.NotFoundException;
import ru.otus.hw.model.DeliveryReservation;
import ru.otus.hw.model.DeliveryReservation.DeliveryReservationStatus;
import ru.otus.hw.repository.DeliveryReservationRepository;

import java.time.LocalDate;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryReservationServiceImpl implements DeliveryReservationService {

    private final DeliveryReservationRepository deliveryReservationRepository;

    private final DeliveryReservationMapper deliveryReservationMapper;

    @Override
    @Transactional(readOnly = true)
    public @NonNull DeliveryReservationResponse getByOrderId(Long orderId) {
        log.debug("Admin: fetching delivery reservation for order id: {}", orderId);
        DeliveryReservation reservation = deliveryReservationRepository.findByOrderId(orderId)
                .orElseThrow(() -> new NotFoundException("Delivery reservation not found for order id: " + orderId));
        return deliveryReservationMapper.toResponse(reservation);
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull DeliveryReservationResponse getById(Long reservationId) {
        log.debug("Admin: fetching delivery reservation by id: {}", reservationId);
        DeliveryReservation reservation = deliveryReservationRepository.findById(reservationId)
                .orElseThrow(() -> new NotFoundException(
                        "Delivery reservation not found with id: " + reservationId));
        return deliveryReservationMapper.toResponse(reservation);
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull Page<DeliveryReservationResponse> getReservations(LocalDate date,
                                                                      DeliveryReservationStatusFilter status,
                                                                      Long courierSlotId,
                                                                      Integer assignedCourierNumber,
                                                                      Pageable pageable) {
        log.debug("Admin: fetching delivery reservations for date: {}, status: {}, courierSlotId: {}, "
                        + "assignedCourierNumber: {}", date, status, courierSlotId, assignedCourierNumber);
        DeliveryReservationStatus statusFilter = status == null ?
                null : DeliveryReservationStatus.valueOf(status.name());
        return deliveryReservationRepository
                .findWithFilters(date, statusFilter, courierSlotId, assignedCourierNumber, pageable)
                .map(deliveryReservationMapper::toResponse);
    }
}
