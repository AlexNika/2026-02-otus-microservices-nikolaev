package ru.otus.hw.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.otus.hw.config.properties.CourierSlotConfig;
import ru.otus.hw.config.properties.CourierSlotProperties.SlotInterval;
import ru.otus.hw.dto.CapacityResponse;
import ru.otus.hw.dto.CapacitySlotResponse;
import ru.otus.hw.dto.SetCourierCapacityRequest;
import ru.otus.hw.dto.mapper.CourierDayCapacityMapper;
import ru.otus.hw.exception.DeliveryReservationException;
import ru.otus.hw.exception.NotFoundException;
import ru.otus.hw.model.CourierDayCapacity;
import ru.otus.hw.model.CourierSlot;
import ru.otus.hw.model.DeliveryReservation;
import ru.otus.hw.repository.CourierDayCapacityRepository;
import ru.otus.hw.repository.CourierSlotRepository;
import ru.otus.hw.repository.DeliveryReservationRepository;
import ru.otus.hw.repository.projection.SlotActiveCountProjection;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryCapacityServiceImpl implements DeliveryCapacityService {

    private final CourierDayCapacityRepository courierDayCapacityRepository;

    private final CourierSlotRepository courierSlotRepository;

    private final DeliveryReservationRepository deliveryReservationRepository;

    private final CourierDayCapacityMapper courierDayCapacityMapper;

    private final CourierSlotConfig courierSlotConfig;

    @Override
    @Transactional
    public @NonNull CapacityResponse setCapacity(LocalDate date, @NonNull SetCourierCapacityRequest request) {
        log.info("Setting courier capacity for date: {}, courierCount: {}", date, request.courierCount());

        CourierDayCapacity dayCapacity = courierDayCapacityRepository.findLockedWithSlotsByCapacityDate(date)
                .orElseGet(() -> courierDayCapacityRepository.save(CourierDayCapacity.builder()
                        .capacityDate(date)
                        .courierCount(request.courierCount())
                        .build()));
        dayCapacity.setCourierCount(request.courierCount());

        List<SlotInterval> targetIntervals = resolveTargetIntervals(request);
        Set<SlotInterval> targetSet = new LinkedHashSet<>(targetIntervals);

        Map<Long, Long> activeCountsBySlotId = deliveryReservationRepository
                .countActiveByDayCapacityIdAndStatuses(dayCapacity.getId(), DeliveryReservation.ACTIVE_STATUSES)
                .stream()
                .collect(Collectors.toMap(SlotActiveCountProjection::getSlotId,
                        SlotActiveCountProjection::getActiveCount));

        Map<SlotInterval, CourierSlot> existingSlots = dayCapacity.getSlots().stream()
                .collect(Collectors.toMap(
                        slot -> new SlotInterval(slot.getSlotStart(), slot.getSlotEnd()),
                        slot -> slot));

        List<CourierSlot> slotsToRemove = existingSlots.entrySet().stream()
                .filter(entry -> !targetSet.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .toList();
        for (CourierSlot slot : slotsToRemove) {
            long activeCount = activeCountsBySlotId.getOrDefault(slot.getId(), 0L);
            if (activeCount > 0) {
                throw DeliveryReservationException.capacityConflict(
                        "Slot " + slot.getSlotStart() + "-" + slot.getSlotEnd()
                                + " has " + activeCount + " active reservation(s) and cannot be removed");
            }
            dayCapacity.getSlots().remove(slot);
            courierSlotRepository.delete(slot);
            log.debug("Removed slot {}-{} for date: {}", slot.getSlotStart(), slot.getSlotEnd(), date);
        }

        for (SlotInterval interval : targetIntervals) {
            if (!existingSlots.containsKey(interval)) {
                CourierSlot newSlot = CourierSlot.builder()
                        .dayCapacity(dayCapacity)
                        .slotStart(interval.start())
                        .slotEnd(interval.end())
                        .build();
                courierSlotRepository.save(newSlot);
                dayCapacity.getSlots().add(newSlot);
                log.debug("Created slot {}-{} for date: {}", interval.start(), interval.end(), date);
            }
        }

        long maxActiveReservationsInSlot = activeCountsBySlotId.values().stream()
                .mapToLong(Long::longValue)
                .max()
                .orElse(0L);
        if (request.courierCount() < maxActiveReservationsInSlot) {
            throw DeliveryReservationException.capacityConflict(
                    "New courier count " + request.courierCount()
                            + " is less than maximum active reservations in a slot: " + maxActiveReservationsInSlot);
        }

        log.info("Courier capacity set for date: {}, courierCount: {}, slots: {}",
                date, request.courierCount(), dayCapacity.getSlots().size());
        return buildCapacityResponse(dayCapacity);
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull CapacityResponse getCapacity(LocalDate date) {
        log.debug("Fetching courier capacity for date: {}", date);
        CourierDayCapacity dayCapacity = courierDayCapacityRepository.findByCapacityDate(date)
                .orElseThrow(() -> new NotFoundException("Courier capacity not configured for date: " + date));
        return buildCapacityResponse(dayCapacity);
    }

    private @NonNull List<SlotInterval> resolveTargetIntervals(@NonNull SetCourierCapacityRequest request) {
        if (request.slots() == null || request.slots().isEmpty()) {
            return courierSlotConfig.getDefaultSlotIntervals();
        }
        return request.slots().stream()
                .map(slot -> new SlotInterval(slot.slotStart(), slot.slotEnd()))
                .toList();
    }

    private @NonNull CapacityResponse buildCapacityResponse(@NonNull CourierDayCapacity dayCapacity) {
        Map<Long, Long> activeCountsBySlotId = deliveryReservationRepository
                .countActiveByCapacityDateAndStatuses(dayCapacity.getCapacityDate(),
                        DeliveryReservation.ACTIVE_STATUSES)
                .stream()
                .collect(Collectors.toMap(SlotActiveCountProjection::getSlotId,
                        SlotActiveCountProjection::getActiveCount));

        int courierCount = dayCapacity.getCourierCount();
        List<CapacitySlotResponse> slots = dayCapacity.getSlots().stream()
                .sorted(Comparator.comparing(CourierSlot::getSlotStart))
                .map(slot -> courierDayCapacityMapper.toCapacitySlotBase(slot).toBuilder()
                        .capacity(courierCount)
                        .reservedCount(activeCountsBySlotId.getOrDefault(slot.getId(), 0L).intValue())
                        .build())
                .toList();

        return CapacityResponse.builder()
                .capacityDate(dayCapacity.getCapacityDate())
                .courierCount(courierCount)
                .slots(slots)
                .build();
    }
}
