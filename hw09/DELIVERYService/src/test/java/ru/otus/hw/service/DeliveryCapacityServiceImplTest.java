package ru.otus.hw.service;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.otus.hw.config.properties.CourierSlotConfig;
import ru.otus.hw.config.properties.CourierSlotProperties.SlotInterval;
import ru.otus.hw.dto.CapacityResponse;
import ru.otus.hw.dto.CapacitySlotResponse;
import ru.otus.hw.dto.SetCourierCapacityRequest;
import ru.otus.hw.dto.SlotIntervalRequestDto;
import ru.otus.hw.dto.mapper.CourierDayCapacityMapper;
import ru.otus.hw.exception.DeliveryReservationException;
import ru.otus.hw.exception.ErrorCodes;
import ru.otus.hw.exception.NotFoundException;
import ru.otus.hw.model.CourierDayCapacity;
import ru.otus.hw.model.CourierSlot;
import ru.otus.hw.model.DeliveryReservation;
import ru.otus.hw.repository.CourierDayCapacityRepository;
import ru.otus.hw.repository.CourierSlotRepository;
import ru.otus.hw.repository.DeliveryReservationRepository;
import ru.otus.hw.repository.projection.SlotActiveCountProjection;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeliveryCapacityServiceImplTest {

    private static final LocalDate DATE = LocalDate.of(2026, 8, 10);

    @Mock
    private CourierDayCapacityRepository courierDayCapacityRepository;

    @Mock
    private CourierSlotRepository courierSlotRepository;

    @Mock
    private DeliveryReservationRepository deliveryReservationRepository;

    @Mock
    private CourierDayCapacityMapper courierDayCapacityMapper;

    @Mock
    private CourierSlotConfig courierSlotConfig;

    @InjectMocks
    private DeliveryCapacityServiceImpl deliveryCapacityService;

    @Test
    @DisplayName("должен создать новую ёмкость с default-слотами, если слоты в запросе не переданы")
    void shouldCreateCapacityWithDefaultSlots() {
        when(courierDayCapacityRepository.findLockedWithSlotsByCapacityDate(DATE)).thenReturn(Optional.empty());
        when(courierDayCapacityRepository
                .save(any(CourierDayCapacity.class)))
                .thenAnswer(invocation -> {
                    CourierDayCapacity saved = invocation.getArgument(0);
                    saved.setId(1L);
                    return saved;
                });
        when(courierSlotConfig.getDefaultSlotIntervals())
                .thenReturn(List.of(new SlotInterval(LocalTime.of(10, 0), LocalTime.of(12, 0))));
        when(deliveryReservationRepository.countActiveByDayCapacityIdAndStatuses(
                1L, DeliveryReservation.ACTIVE_STATUSES))
                .thenReturn(List.of());
        when(deliveryReservationRepository.countActiveByCapacityDateAndStatuses(
                DATE, DeliveryReservation.ACTIVE_STATUSES))
                .thenReturn(List.of());
        mockCapacitySlotBaseMapping();

        var request = new SetCourierCapacityRequest(3, null);

        CapacityResponse response = deliveryCapacityService.setCapacity(DATE, request);

        assertThat(response.capacityDate()).isEqualTo(DATE);
        assertThat(response.courierCount()).isEqualTo(3);
        assertThat(response.slots()).hasSize(1);
        assertThat(response.slots().getFirst().slotStart()).isEqualTo(LocalTime.of(10, 0));
        assertThat(response.slots().getFirst().capacity()).isEqualTo(3);
        assertThat(response.slots().getFirst().reservedCount()).isZero();
        verify(courierSlotRepository).save(any(CourierSlot.class));
        verify(courierSlotRepository, never()).delete(any(CourierSlot.class));
    }

    @Test
    @DisplayName("должен удалить слот без активных резервов и создать новый при обновлении ёмкости")
    void shouldReplaceSlotsWithoutActiveReservations() {
        CourierDayCapacity dayCapacity = dayCapacityWithSlot(10L,
                LocalTime.of(10, 0), LocalTime.of(12, 0));
        when(courierDayCapacityRepository.findLockedWithSlotsByCapacityDate(DATE))
                .thenReturn(Optional.of(dayCapacity));
        when(deliveryReservationRepository.countActiveByDayCapacityIdAndStatuses(
                1L, DeliveryReservation.ACTIVE_STATUSES))
                .thenReturn(List.of());
        when(deliveryReservationRepository.countActiveByCapacityDateAndStatuses(
                DATE, DeliveryReservation.ACTIVE_STATUSES))
                .thenReturn(List.of());
        mockCapacitySlotBaseMapping();

        var request = new SetCourierCapacityRequest(2,
                List.of(new SlotIntervalRequestDto(LocalTime.of(12, 0), LocalTime.of(14, 0))));

        CapacityResponse response = deliveryCapacityService.setCapacity(DATE, request);

        verify(courierSlotRepository).delete(any(CourierSlot.class));
        verify(courierSlotRepository).save(any(CourierSlot.class));
        assertThat(response.courierCount()).isEqualTo(2);
        assertThat(response.slots()).hasSize(1);
        assertThat(response.slots().getFirst().slotStart()).isEqualTo(LocalTime.of(12, 0));
        assertThat(response.slots().getFirst().slotEnd()).isEqualTo(LocalTime.of(14, 0));
    }

    @Test
    @DisplayName("должен бросить DELIVERY_CAPACITY_CONFLICT при удалении слота с активными резервами")
    void shouldThrowCapacityConflictWhenRemovingSlotWithActiveReservations() {
        CourierDayCapacity dayCapacity = dayCapacityWithSlot(10L,
                LocalTime.of(10, 0), LocalTime.of(12, 0));
        SlotActiveCountProjection activeCount = projection(10L, 1L);
        when(courierDayCapacityRepository.findLockedWithSlotsByCapacityDate(DATE))
                .thenReturn(Optional.of(dayCapacity));
        when(deliveryReservationRepository.countActiveByDayCapacityIdAndStatuses(
                1L, DeliveryReservation.ACTIVE_STATUSES))
                .thenReturn(List.of(activeCount));

        var request = new SetCourierCapacityRequest(2,
                List.of(new SlotIntervalRequestDto(LocalTime.of(12, 0), LocalTime.of(14, 0))));

        DeliveryReservationException ex = assertThrows(DeliveryReservationException.class,
                () -> deliveryCapacityService.setCapacity(DATE, request));

        assertThat(ex.getCode()).isEqualTo(ErrorCodes.DELIVERY_CAPACITY_CONFLICT);
        verify(courierSlotRepository, never()).delete(any(CourierSlot.class));
        verify(courierSlotRepository, never()).save(any(CourierSlot.class));
    }

    @Test
    @DisplayName("должен бросить DELIVERY_CAPACITY_CONFLICT, если новый courierCount меньше активных резервов в слоте")
    void shouldThrowCapacityConflictWhenCourierCountIsBelowActiveReservations() {
        CourierDayCapacity dayCapacity = dayCapacityWithSlot(10L,
                LocalTime.of(10, 0), LocalTime.of(12, 0));
        SlotActiveCountProjection activeCount = projection(10L, 3L);
        when(courierDayCapacityRepository.findLockedWithSlotsByCapacityDate(DATE))
                .thenReturn(Optional.of(dayCapacity));
        when(deliveryReservationRepository.countActiveByDayCapacityIdAndStatuses(
                1L, DeliveryReservation.ACTIVE_STATUSES))
                .thenReturn(List.of(activeCount));

        var request = new SetCourierCapacityRequest(2,
                List.of(new SlotIntervalRequestDto(LocalTime.of(10, 0), LocalTime.of(12, 0))));

        DeliveryReservationException ex = assertThrows(DeliveryReservationException.class,
                () -> deliveryCapacityService.setCapacity(DATE, request));

        assertThat(ex.getCode()).isEqualTo(ErrorCodes.DELIVERY_CAPACITY_CONFLICT);
        verify(courierSlotRepository, never()).delete(any(CourierSlot.class));
        verify(courierSlotRepository, never()).save(any(CourierSlot.class));
    }

    @Test
    @DisplayName("должен вернуть ёмкость с заполненными capacity и reservedCount в слотах")
    void shouldReturnCapacityWithReservedCounts() {
        CourierDayCapacity dayCapacity = dayCapacityWithSlot(10L,
                LocalTime.of(10, 0), LocalTime.of(12, 0));
        SlotActiveCountProjection activeCount = projection(10L, 1L);
        when(courierDayCapacityRepository.findByCapacityDate(DATE)).thenReturn(Optional.of(dayCapacity));
        when(deliveryReservationRepository.countActiveByCapacityDateAndStatuses(
                DATE, DeliveryReservation.ACTIVE_STATUSES))
                .thenReturn(List.of(activeCount));
        mockCapacitySlotBaseMapping();

        CapacityResponse response = deliveryCapacityService.getCapacity(DATE);

        assertThat(response.capacityDate()).isEqualTo(DATE);
        assertThat(response.courierCount()).isEqualTo(2);
        assertThat(response.slots()).hasSize(1);
        assertThat(response.slots().getFirst().slotId()).isEqualTo(10L);
        assertThat(response.slots().getFirst().capacity()).isEqualTo(2);
        assertThat(response.slots().getFirst().reservedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("должен бросить NotFoundException, если ёмкость на дату не настроена")
    void shouldThrowNotFoundWhenCapacityIsNotConfigured() {
        when(courierDayCapacityRepository.findByCapacityDate(DATE)).thenReturn(Optional.empty());

        NotFoundException ex = assertThrows(NotFoundException.class,
                () -> deliveryCapacityService.getCapacity(DATE));

        assertThat(ex.getMessage()).contains(DATE.toString());
    }

    private @NonNull CourierDayCapacity dayCapacityWithSlot(Long slotId, LocalTime start, LocalTime end) {
        CourierDayCapacity dayCapacity = CourierDayCapacity.builder()
                .capacityDate(DATE)
                .courierCount(2)
                .slots(new ArrayList<>())
                .build();
        dayCapacity.setId(1L);

        CourierSlot slot = CourierSlot.builder()
                .dayCapacity(dayCapacity)
                .slotStart(start)
                .slotEnd(end)
                .build();
        slot.setId(slotId);
        dayCapacity.getSlots().add(slot);
        return dayCapacity;
    }

    private void mockCapacitySlotBaseMapping() {
        when(courierDayCapacityMapper.toCapacitySlotBase(any(CourierSlot.class))).thenAnswer(invocation -> {
            CourierSlot slot = invocation.getArgument(0);
            return CapacitySlotResponse.builder()
                    .slotId(slot.getId())
                    .slotStart(slot.getSlotStart())
                    .slotEnd(slot.getSlotEnd())
                    .build();
        });
    }

    private @NonNull SlotActiveCountProjection projection(Long slotId, Long activeCount) {
        SlotActiveCountProjection projection = mock(SlotActiveCountProjection.class);
        when(projection.getSlotId()).thenReturn(slotId);
        when(projection.getActiveCount()).thenReturn(activeCount);
        return projection;
    }
}
