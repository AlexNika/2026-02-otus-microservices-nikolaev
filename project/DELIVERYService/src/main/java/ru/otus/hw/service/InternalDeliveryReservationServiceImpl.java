package ru.otus.hw.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.otus.hw.dto.CancelDeliveryResponse;
import ru.otus.hw.dto.CancelDeliveryResponse.CancelDeliveryResult;
import ru.otus.hw.dto.DeliveryReservationResponse;
import ru.otus.hw.dto.ReserveDeliveryRequest;
import ru.otus.hw.dto.mapper.DeliveryReservationMapper;
import ru.otus.hw.exception.CourierAssignmentException;
import ru.otus.hw.exception.DeliveryReservationException;
import ru.otus.hw.exception.NotFoundException;
import ru.otus.hw.model.CourierSlot;
import ru.otus.hw.model.DeliveryReservation;
import ru.otus.hw.model.DeliveryReservation.DeliveryReservationStatus;
import ru.otus.hw.repository.CourierSlotRepository;
import ru.otus.hw.repository.DeliveryReservationRepository;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class InternalDeliveryReservationServiceImpl implements InternalDeliveryReservationService {

    private final DeliveryReservationRepository deliveryReservationRepository;

    private final CourierSlotRepository courierSlotRepository;

    private final DeliveryReservationMapper mapper;

    @Override
    @Transactional
    public @NonNull ReserveDeliveryResult reserve(@NonNull ReserveDeliveryRequest request) {
        log.info("Reserving delivery for order id: {}, date: {}, slot: {}-{}",
                request.orderId(), request.date(), request.slotStart(), request.slotEnd());

        Optional<DeliveryReservation> existing = deliveryReservationRepository.findByOrderId(request.orderId());
        if (existing.isPresent()) {
            verifyReplayPayload(request, existing.get());
            log.info("Delivery reservation for order id: {} already exists in status {}, returning existing "
                    + "(idempotent replay)", request.orderId(), existing.get().getStatus());
            return new ReserveDeliveryResult(mapper.toResponse(existing.get()), false);
        }

        CourierSlot slot = courierSlotRepository
                .findLockedByDateAndTimeSlot(request.date(), request.slotStart(), request.slotEnd())
                .orElseThrow(() -> new NotFoundException(
                        "Delivery slot not configured for date " + request.date()
                                + " and interval " + request.slotStart() + "-" + request.slotEnd()));

        int capacity = slot.getDayCapacity().getCourierCount();
        long reserved = deliveryReservationRepository
                .countByCourierSlotIdAndStatusIn(slot.getId(), DeliveryReservation.ACTIVE_STATUSES);
        if (reserved >= capacity) {
            throw DeliveryReservationException.noFreeCourier(
                    request.date(), request.slotStart(), request.slotEnd(), capacity, reserved);
        }

        int courierNumber = findFirstFreeCourierNumber(slot.getId(), capacity);

        DeliveryReservation reservation = deliveryReservationRepository.save(DeliveryReservation.builder()
                .orderId(request.orderId())
                .userId(request.userId())
                .courierSlot(slot)
                .assignedCourierNumber(courierNumber)
                .status(DeliveryReservationStatus.RESERVED)
                .build());
        log.info("Delivery reserved for order id: {}, courier number: {}", request.orderId(), courierNumber);
        return new ReserveDeliveryResult(mapper.toResponse(reservation), true);
    }

    @Override
    @Transactional
    public @NonNull DeliveryReservationResponse confirm(Long orderId) {
        log.info("Confirming delivery reservation for order id: {}", orderId);
        DeliveryReservation reservation = getLockedByOrderId(orderId);
        switch (reservation.getStatus()) {
            case RESERVED -> {
                reservation.setStatus(DeliveryReservationStatus.CONFIRMED);
                log.debug("Delivery reservation for order id: {} confirmed", orderId);
            }
            case CONFIRMED -> log.debug("Delivery reservation for order id: {} already confirmed, no-op", orderId);
            default -> throw DeliveryReservationException.stateConflict(
                    orderId, reservation.getStatus().name(), "confirm");
        }
        deliveryReservationRepository.save(reservation);
        return mapper.toResponse(reservation);
    }

    @Override
    @Transactional
    public @NonNull CancelDeliveryResponse cancel(Long orderId) {
        log.info("Cancelling delivery reservation for order id: {}", orderId);
        Optional<DeliveryReservation> found = deliveryReservationRepository.findLockedByOrderId(orderId);
        if (found.isEmpty()) {
            log.info("Delivery reservation for order id: {} not found, compensation is a no-op", orderId);
            return CancelDeliveryResponse.builder()
                    .orderId(orderId)
                    .result(CancelDeliveryResult.NOT_FOUND)
                    .build();
        }

        DeliveryReservation reservation = found.get();
        return switch (reservation.getStatus()) {
            case RESERVED -> {
                reservation.setStatus(DeliveryReservationStatus.CANCELLED);
                deliveryReservationRepository.save(reservation);
                log.info("Delivery reservation for order id: {} cancelled", orderId);
                yield CancelDeliveryResponse.builder()
                        .orderId(orderId)
                        .result(CancelDeliveryResult.CANCELLED)
                        .build();
            }
            case CANCELLED, FAILED -> {
                log.debug("Delivery reservation for order id: {} already in terminal status {}",
                        orderId, reservation.getStatus());
                yield CancelDeliveryResponse.builder()
                        .orderId(orderId)
                        .result(CancelDeliveryResult.ALREADY_CANCELLED)
                        .build();
            }
            case CONFIRMED ->
                    throw DeliveryReservationException.stateConflict(orderId, reservation.getStatus().name(),
                            "cancel");
        };
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull DeliveryReservationResponse getByOrderId(Long orderId) {
        DeliveryReservation reservation = deliveryReservationRepository.findByOrderId(orderId)
                .orElseThrow(() -> new NotFoundException("Delivery reservation not found for order id: " + orderId));
        return mapper.toResponse(reservation);
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull DeliveryReservationResponse getById(Long reservationId) {
        DeliveryReservation reservation = deliveryReservationRepository.findById(reservationId)
                .orElseThrow(() -> new NotFoundException(
                        "Delivery reservation not found with id: " + reservationId));
        return mapper.toResponse(reservation);
    }

    private @NonNull DeliveryReservation getLockedByOrderId(Long orderId) {
        return deliveryReservationRepository.findLockedByOrderId(orderId)
                .orElseThrow(() -> new NotFoundException("Delivery reservation not found for order id: " + orderId));
    }

    /**
     * Идемпотентный replay со сверкой payload'а: существующая бронь по orderId возвращается
     * только при совпадении date/slotStart/slotEnd; несовпадение - 409
     * IDEMPOTENCY_KEY_CONFLICT.
     */
    private void verifyReplayPayload(@NonNull ReserveDeliveryRequest request,
                                     @NonNull DeliveryReservation existing) {
        CourierSlot slot = existing.getCourierSlot();
        LocalDate existingDate = slot.getDayCapacity().getCapacityDate();
        LocalTime existingStart = slot.getSlotStart();
        LocalTime existingEnd = slot.getSlotEnd();
        if (!Objects.equals(existingDate, request.date())
                || !Objects.equals(existingStart, request.slotStart())
                || !Objects.equals(existingEnd, request.slotEnd())) {
            throw DeliveryReservationException.idempotencyConflict(request.orderId(),
                    String.format("reservation already exists for date=%s slot=%s-%s, requested date=%s slot=%s-%s",
                            existingDate, existingStart, existingEnd,
                            request.date(), request.slotStart(), request.slotEnd()));
        }
    }

    private int findFirstFreeCourierNumber(Long slotId, int capacity) {
        List<Integer> busyNumbers = deliveryReservationRepository
                .findActiveAssignedCourierNumbersByCourierSlotIdAndStatusIn(slotId, DeliveryReservation.ACTIVE_STATUSES);
        Set<Integer> busy = new HashSet<>(busyNumbers);
        for (int number = 1; number <= capacity; number++) {
            if (!busy.contains(number)) {
                return number;
            }
        }
        throw CourierAssignmentException.courierAssignmentFailed(slotId, capacity);
    }
}
