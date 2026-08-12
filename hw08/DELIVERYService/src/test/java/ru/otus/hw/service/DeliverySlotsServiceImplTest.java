package ru.otus.hw.service;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.otus.hw.dto.AvailableSlotsResponse;
import ru.otus.hw.model.CourierDayCapacity;
import ru.otus.hw.model.CourierSlot;
import ru.otus.hw.model.DeliveryReservation;
import ru.otus.hw.repository.CourierSlotRepository;
import ru.otus.hw.repository.DeliveryReservationRepository;
import ru.otus.hw.repository.projection.SlotActiveCountProjection;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeliverySlotsServiceImplTest {

    private static final LocalDate DATE = LocalDate.of(2026, 8, 10);

    @Mock
    private CourierSlotRepository courierSlotRepository;

    @Mock
    private DeliveryReservationRepository deliveryReservationRepository;

    @InjectMocks
    private DeliverySlotsServiceImpl deliverySlotsService;

    @Test
    @DisplayName("должен вернуть пустой список слотов, если ёмкость на дату не настроена")
    void shouldReturnEmptySlotsWhenCapacityIsNotConfigured() {
        when(courierSlotRepository.findByCapacityDateOrderBySlotStartAsc(DATE)).thenReturn(List.of());

        AvailableSlotsResponse response = deliverySlotsService.getAvailableSlots(DATE);

        assertThat(response.date()).isEqualTo(DATE);
        assertThat(response.slots()).isEmpty();
        verifyNoInteractions(deliveryReservationRepository);
    }

    @Test
    @DisplayName("должен вернуть available=true для слота со свободными курьерами и available=false для заполненного")
    void shouldReturnSlotAvailability() {
        CourierDayCapacity dayCapacity = CourierDayCapacity.builder()
                .capacityDate(DATE)
                .courierCount(2)
                .build();
        dayCapacity.setId(1L);

        CourierSlot freeSlot = slot(10L, LocalTime.of(10, 0), LocalTime.of(12, 0), dayCapacity);
        CourierSlot fullSlot = slot(11L, LocalTime.of(12, 0), LocalTime.of(14, 0), dayCapacity);
        SlotActiveCountProjection freeSlotCount = projection(10L, 1L);
        SlotActiveCountProjection fullSlotCount = projection(11L, 2L);
        when(courierSlotRepository.findByCapacityDateOrderBySlotStartAsc(DATE))
                .thenReturn(List.of(freeSlot, fullSlot));

        when(deliveryReservationRepository.countActiveByCapacityDateAndStatuses(
                DATE, DeliveryReservation.ACTIVE_STATUSES))
                .thenReturn(List.of(freeSlotCount, fullSlotCount));

        AvailableSlotsResponse response = deliverySlotsService.getAvailableSlots(DATE);

        assertThat(response.date()).isEqualTo(DATE);
        assertThat(response.slots()).hasSize(2);
        assertThat(response.slots().getFirst().slotStart()).isEqualTo(LocalTime.of(10, 0));
        assertThat(response.slots().getFirst().available()).isTrue();
        assertThat(response.slots().get(1).slotStart()).isEqualTo(LocalTime.of(12, 0));
        assertThat(response.slots().get(1).available()).isFalse();
    }

    @Test
    @DisplayName("должен вернуть available=false для всех слотов при courierCount = 0")
    void shouldReturnUnavailableSlotsWhenCourierCountIsZero() {
        CourierDayCapacity dayCapacity = CourierDayCapacity.builder()
                .capacityDate(DATE)
                .courierCount(0)
                .build();
        dayCapacity.setId(1L);

        CourierSlot slot = slot(10L, LocalTime.of(10, 0), LocalTime.of(12, 0), dayCapacity);
        when(courierSlotRepository.findByCapacityDateOrderBySlotStartAsc(DATE)).thenReturn(List.of(slot));
        when(deliveryReservationRepository.countActiveByCapacityDateAndStatuses(
                DATE, DeliveryReservation.ACTIVE_STATUSES))
                .thenReturn(List.of());

        AvailableSlotsResponse response = deliverySlotsService.getAvailableSlots(DATE);

        assertThat(response.slots()).hasSize(1);
        assertThat(response.slots().getFirst().available()).isFalse();
    }

    private @NonNull CourierSlot slot(Long id, LocalTime start, LocalTime end, CourierDayCapacity dayCapacity) {
        CourierSlot slot = CourierSlot.builder()
                .dayCapacity(dayCapacity)
                .slotStart(start)
                .slotEnd(end)
                .build();
        slot.setId(id);
        return slot;
    }

    private @NonNull SlotActiveCountProjection projection(Long slotId, Long activeCount) {
        SlotActiveCountProjection projection = mock(SlotActiveCountProjection.class);
        when(projection.getSlotId()).thenReturn(slotId);
        when(projection.getActiveCount()).thenReturn(activeCount);
        return projection;
    }
}
