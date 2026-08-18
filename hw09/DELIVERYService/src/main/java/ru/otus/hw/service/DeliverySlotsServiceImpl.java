package ru.otus.hw.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.otus.hw.dto.AvailableSlotResponse;
import ru.otus.hw.dto.AvailableSlotsResponse;
import ru.otus.hw.model.CourierSlot;
import ru.otus.hw.model.DeliveryReservation;
import ru.otus.hw.repository.CourierSlotRepository;
import ru.otus.hw.repository.DeliveryReservationRepository;
import ru.otus.hw.repository.projection.SlotActiveCountProjection;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeliverySlotsServiceImpl implements DeliverySlotsService {

    private final CourierSlotRepository courierSlotRepository;

    private final DeliveryReservationRepository deliveryReservationRepository;

    @Override
    @Transactional(readOnly = true)
    public @NonNull AvailableSlotsResponse getAvailableSlots(LocalDate date) {
        log.debug("Fetching available slots for date: {}", date);
        List<CourierSlot> slots = courierSlotRepository.findByCapacityDateOrderBySlotStartAsc(date);
        if (slots.isEmpty()) {
            log.debug("No slots configured for date: {}", date);
            return AvailableSlotsResponse.builder()
                    .date(date)
                    .slots(List.of())
                    .build();
        }

        int courierCount = slots.getFirst().getDayCapacity().getCourierCount();
        Map<Long, Long> activeCountsBySlotId = deliveryReservationRepository
                .countActiveByCapacityDateAndStatuses(date, DeliveryReservation.ACTIVE_STATUSES)
                .stream()
                .collect(Collectors.toMap(SlotActiveCountProjection::getSlotId,
                        SlotActiveCountProjection::getActiveCount));

        List<AvailableSlotResponse> slotResponses = slots.stream()
                .map(slot -> {
                    long reservedCount = activeCountsBySlotId.getOrDefault(slot.getId(), 0L);
                    boolean available = courierCount > 0 && reservedCount < courierCount;
                    return AvailableSlotResponse.builder()
                            .slotStart(slot.getSlotStart())
                            .slotEnd(slot.getSlotEnd())
                            .available(available)
                            .build();
                })
                .toList();

        return AvailableSlotsResponse.builder()
                .date(date)
                .slots(slotResponses)
                .build();
    }
}
