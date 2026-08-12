package ru.otus.hw.service;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.otus.hw.dto.CancelDeliveryResponse;
import ru.otus.hw.dto.CancelDeliveryResponse.CancelDeliveryResult;
import ru.otus.hw.dto.DeliveryReservationResponse;
import ru.otus.hw.dto.ReserveDeliveryRequest;
import ru.otus.hw.dto.mapper.DeliveryReservationMapper;
import ru.otus.hw.exception.CourierAssignmentException;
import ru.otus.hw.exception.DeliveryReservationException;
import ru.otus.hw.exception.ErrorCodes;
import ru.otus.hw.exception.NotFoundException;
import ru.otus.hw.model.CourierDayCapacity;
import ru.otus.hw.model.CourierSlot;
import ru.otus.hw.model.DeliveryReservation;
import ru.otus.hw.model.DeliveryReservation.DeliveryReservationStatus;
import ru.otus.hw.repository.CourierSlotRepository;
import ru.otus.hw.repository.DeliveryReservationRepository;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InternalDeliveryReservationServiceImplTest {

    private static final Long ORDER_ID = 100L;

    private static final LocalDate DATE = LocalDate.of(2026, 8, 10);

    private static final LocalTime SLOT_START = LocalTime.of(10, 0);

    private static final LocalTime SLOT_END = LocalTime.of(12, 0);

    private static final ReserveDeliveryRequest REQUEST = new ReserveDeliveryRequest(
            ORDER_ID, DATE, SLOT_START, SLOT_END);

    private static final DeliveryReservationResponse RESPONSE = DeliveryReservationResponse.builder()
            .reservationId(55L)
            .orderId(ORDER_ID)
            .date(DATE)
            .slotStart(SLOT_START)
            .slotEnd(SLOT_END)
            .assignedCourierNumber(1)
            .status("RESERVED")
            .build();

    @Mock
    private DeliveryReservationRepository deliveryReservationRepository;

    @Mock
    private CourierSlotRepository courierSlotRepository;

    @Mock
    private DeliveryReservationMapper deliveryReservationMapper;

    @InjectMocks
    private InternalDeliveryReservationServiceImpl reservationService;

    @Test
    @DisplayName("reserve: должен идемпотентно вернуть существующий резерв с created=false")
    void shouldReturnExistingReservationIdempotently() {
        DeliveryReservation existing = reservation(DeliveryReservationStatus.CANCELLED);
        when(deliveryReservationRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(existing));
        when(deliveryReservationMapper.toResponse(existing)).thenReturn(RESPONSE);

        ReserveDeliveryResult result = reservationService.reserve(REQUEST);

        assertThat(result.created()).isFalse();
        assertThat(result.response()).isEqualTo(RESPONSE);
        verifyNoInteractions(courierSlotRepository);
        verify(deliveryReservationRepository, never()).save(any());
    }

    @Test
    @DisplayName("reserve: должен бросить NotFoundException, если слот на дату и интервал не настроен")
    void shouldThrowNotFoundWhenSlotIsNotConfigured() {
        when(deliveryReservationRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());
        when(courierSlotRepository.findLockedByDateAndTimeSlot(DATE, SLOT_START, SLOT_END))
                .thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> reservationService.reserve(REQUEST));
        verify(deliveryReservationRepository, never()).save(any());
    }

    @Test
    @DisplayName("reserve: должен бросить DELIVERY_NO_FREE_COURIER, когда слот заполнен")
    void shouldThrowNoFreeCourierWhenSlotIsFull() {
        CourierSlot slot = slotWithCapacity(1);
        when(deliveryReservationRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());
        when(courierSlotRepository.findLockedByDateAndTimeSlot(DATE, SLOT_START, SLOT_END))
                .thenReturn(Optional.of(slot));
        when(deliveryReservationRepository.countByCourierSlotIdAndStatusIn(
                slot.getId(), DeliveryReservation.ACTIVE_STATUSES))
                .thenReturn(1L);

        DeliveryReservationException ex = assertThrows(DeliveryReservationException.class,
                () -> reservationService.reserve(REQUEST));

        assertThat(ex.getCode()).isEqualTo(ErrorCodes.DELIVERY_NO_FREE_COURIER);
        verify(deliveryReservationRepository, never()).save(any());
    }

    @Test
    @DisplayName("reserve: должен создать резерв с первым свободным номером курьера и created=true")
    void shouldCreateReservationWithFirstFreeCourierNumber() {
        CourierSlot slot = slotWithCapacity(2);
        when(deliveryReservationRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());
        when(courierSlotRepository.findLockedByDateAndTimeSlot(DATE, SLOT_START, SLOT_END))
                .thenReturn(Optional.of(slot));
        when(deliveryReservationRepository.countByCourierSlotIdAndStatusIn(
                slot.getId(), DeliveryReservation.ACTIVE_STATUSES))
                .thenReturn(1L);
        when(deliveryReservationRepository.findActiveAssignedCourierNumbersByCourierSlotIdAndStatusIn(
                slot.getId(), DeliveryReservation.ACTIVE_STATUSES))
                .thenReturn(List.of(1));
        when(deliveryReservationRepository.save(any(DeliveryReservation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(deliveryReservationMapper.toResponse(any(DeliveryReservation.class))).thenReturn(RESPONSE);

        ReserveDeliveryResult result = reservationService.reserve(REQUEST);

        assertThat(result.created()).isTrue();
        assertThat(result.response()).isEqualTo(RESPONSE);

        ArgumentCaptor<DeliveryReservation> captor = ArgumentCaptor.forClass(DeliveryReservation.class);
        verify(deliveryReservationRepository).save(captor.capture());
        DeliveryReservation saved = captor.getValue();
        assertThat(saved.getOrderId()).isEqualTo(ORDER_ID);
        assertThat(saved.getCourierSlot()).isSameAs(slot);
        assertThat(saved.getAssignedCourierNumber()).isEqualTo(2);
        assertThat(saved.getStatus()).isEqualTo(DeliveryReservationStatus.RESERVED);
    }

    @Test
    @DisplayName("reserve: должен бросить DELIVERY_COURIER_ASSIGNMENT_FAILED, если все номера курьеров заняты")
    void shouldThrowCourierAssignmentFailedWhenAllCourierNumbersAreBusy() {
        CourierSlot slot = slotWithCapacity(2);
        when(deliveryReservationRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());
        when(courierSlotRepository.findLockedByDateAndTimeSlot(DATE, SLOT_START, SLOT_END))
                .thenReturn(Optional.of(slot));
        when(deliveryReservationRepository.countByCourierSlotIdAndStatusIn(
                slot.getId(), DeliveryReservation.ACTIVE_STATUSES))
                .thenReturn(1L);
        when(deliveryReservationRepository.findActiveAssignedCourierNumbersByCourierSlotIdAndStatusIn(
                slot.getId(), DeliveryReservation.ACTIVE_STATUSES))
                .thenReturn(List.of(1, 2));

        CourierAssignmentException ex = assertThrows(CourierAssignmentException.class,
                () -> reservationService.reserve(REQUEST));

        assertThat(ex.getCode()).isEqualTo(ErrorCodes.DELIVERY_COURIER_ASSIGNMENT_FAILED);
        verify(deliveryReservationRepository, never()).save(any());
    }

    @Test
    @DisplayName("confirm: должен перевести резерв из RESERVED в CONFIRMED")
    void shouldConfirmReservedReservation() {
        DeliveryReservation reservation = reservation(DeliveryReservationStatus.RESERVED);
        when(deliveryReservationRepository.findLockedByOrderId(ORDER_ID)).thenReturn(Optional.of(reservation));
        when(deliveryReservationRepository.save(reservation)).thenReturn(reservation);
        when(deliveryReservationMapper.toResponse(reservation)).thenReturn(RESPONSE);

        DeliveryReservationResponse response = reservationService.confirm(ORDER_ID);

        assertThat(reservation.getStatus()).isEqualTo(DeliveryReservationStatus.CONFIRMED);
        assertThat(response).isEqualTo(RESPONSE);
    }

    @Test
    @DisplayName("confirm: должен идемпотентно вернуть уже подтверждённый резерв")
    void shouldConfirmIdempotentlyWhenAlreadyConfirmed() {
        DeliveryReservation reservation = reservation(DeliveryReservationStatus.CONFIRMED);
        when(deliveryReservationRepository.findLockedByOrderId(ORDER_ID)).thenReturn(Optional.of(reservation));
        when(deliveryReservationRepository.save(reservation)).thenReturn(reservation);
        when(deliveryReservationMapper.toResponse(reservation)).thenReturn(RESPONSE);

        DeliveryReservationResponse response = reservationService.confirm(ORDER_ID);

        assertThat(reservation.getStatus()).isEqualTo(DeliveryReservationStatus.CONFIRMED);
        assertThat(response).isEqualTo(RESPONSE);
    }

    @Test
    @DisplayName("confirm: должен бросить DELIVERY_RESERVATION_STATE_CONFLICT для отменённого резерва")
    void shouldThrowStateConflictOnConfirmOfCancelledReservation() {
        DeliveryReservation reservation = reservation(DeliveryReservationStatus.CANCELLED);
        when(deliveryReservationRepository.findLockedByOrderId(ORDER_ID)).thenReturn(Optional.of(reservation));

        DeliveryReservationException ex = assertThrows(DeliveryReservationException.class,
                () -> reservationService.confirm(ORDER_ID));

        assertThat(ex.getCode()).isEqualTo(ErrorCodes.DELIVERY_RESERVATION_STATE_CONFLICT);
        verify(deliveryReservationRepository, never()).save(any());
    }

    @Test
    @DisplayName("cancel: должен отменить резерв в статусе RESERVED")
    void shouldCancelReservedReservation() {
        DeliveryReservation reservation = reservation(DeliveryReservationStatus.RESERVED);
        when(deliveryReservationRepository.findLockedByOrderId(ORDER_ID)).thenReturn(Optional.of(reservation));
        when(deliveryReservationRepository.save(reservation)).thenReturn(reservation);

        CancelDeliveryResponse response = reservationService.cancel(ORDER_ID);

        assertThat(reservation.getStatus()).isEqualTo(DeliveryReservationStatus.CANCELLED);
        assertThat(response.orderId()).isEqualTo(ORDER_ID);
        assertThat(response.result()).isEqualTo(CancelDeliveryResult.CANCELLED);
    }

    @Test
    @DisplayName("cancel: должен вернуть ALREADY_CANCELLED для резерва в терминальном статусе")
    void shouldReturnAlreadyCancelledForTerminalStatus() {
        DeliveryReservation reservation = reservation(DeliveryReservationStatus.CANCELLED);
        when(deliveryReservationRepository.findLockedByOrderId(ORDER_ID)).thenReturn(Optional.of(reservation));

        CancelDeliveryResponse response = reservationService.cancel(ORDER_ID);

        assertThat(response.result()).isEqualTo(CancelDeliveryResult.ALREADY_CANCELLED);
        verify(deliveryReservationRepository, never()).save(any());
    }

    @Test
    @DisplayName("cancel: должен вернуть NOT_FOUND, если резерв не найден (компенсация не ломается)")
    void shouldReturnNotFoundWhenReservationIsMissing() {
        when(deliveryReservationRepository.findLockedByOrderId(ORDER_ID)).thenReturn(Optional.empty());

        CancelDeliveryResponse response = reservationService.cancel(ORDER_ID);

        assertThat(response.orderId()).isEqualTo(ORDER_ID);
        assertThat(response.result()).isEqualTo(CancelDeliveryResult.NOT_FOUND);
        verify(deliveryReservationRepository, never()).save(any());
    }

    @Test
    @DisplayName("cancel: должен бросить DELIVERY_RESERVATION_STATE_CONFLICT для подтверждённого резерва")
    void shouldThrowStateConflictOnCancelOfConfirmedReservation() {
        DeliveryReservation reservation = reservation(DeliveryReservationStatus.CONFIRMED);
        when(deliveryReservationRepository.findLockedByOrderId(ORDER_ID)).thenReturn(Optional.of(reservation));

        DeliveryReservationException ex = assertThrows(DeliveryReservationException.class,
                () -> reservationService.cancel(ORDER_ID));

        assertThat(ex.getCode()).isEqualTo(ErrorCodes.DELIVERY_RESERVATION_STATE_CONFLICT);
        verify(deliveryReservationRepository, never()).save(any());
    }

    @Test
    @DisplayName("getByOrderId: должен бросить NotFoundException, если резерв не найден")
    void shouldThrowNotFoundOnGetByOrderId() {
        when(deliveryReservationRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> reservationService.getByOrderId(ORDER_ID));
    }

    private @NonNull CourierSlot slotWithCapacity(int courierCount) {
        CourierDayCapacity dayCapacity = CourierDayCapacity.builder()
                .capacityDate(DATE)
                .courierCount(courierCount)
                .build();
        dayCapacity.setId(1L);
        CourierSlot slot = CourierSlot.builder()
                .dayCapacity(dayCapacity)
                .slotStart(SLOT_START)
                .slotEnd(SLOT_END)
                .build();
        slot.setId(10L);
        return slot;
    }

    private @NonNull DeliveryReservation reservation(DeliveryReservationStatus status) {
        DeliveryReservation reservation = DeliveryReservation.builder()
                .orderId(ORDER_ID)
                .courierSlot(slotWithCapacity(2))
                .assignedCourierNumber(1)
                .status(status)
                .build();
        reservation.setId(55L);
        return reservation;
    }
}
