package ru.otus.hw.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import ru.otus.hw.controller.DeliveryReservation.DeliveryReservationStatusFilter;
import ru.otus.hw.dto.DeliveryReservationResponse;
import ru.otus.hw.dto.mapper.DeliveryReservationMapper;
import ru.otus.hw.exception.NotFoundException;
import ru.otus.hw.model.DeliveryReservation;
import ru.otus.hw.model.DeliveryReservation.DeliveryReservationStatus;
import ru.otus.hw.repository.DeliveryReservationRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeliveryReservationServiceImplTest {

    private static final Long ORDER_ID = 100L;

    private static final Long RESERVATION_ID = 55L;

    private static final LocalDate DATE = LocalDate.of(2026, 8, 10);

    private static final DeliveryReservationResponse RESPONSE = DeliveryReservationResponse.builder()
            .reservationId(RESERVATION_ID)
            .orderId(ORDER_ID)
            .status("RESERVED")
            .build();

    @Mock
    private DeliveryReservationRepository deliveryReservationRepository;

    @Mock
    private DeliveryReservationMapper deliveryReservationMapper;

    @InjectMocks
    private DeliveryReservationServiceImpl adminService;

    @Test
    @DisplayName("должен вернуть резерв по orderId")
    void shouldReturnReservationByOrderId() {
        DeliveryReservation reservation = DeliveryReservation.builder().orderId(ORDER_ID).build();
        when(deliveryReservationRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(reservation));
        when(deliveryReservationMapper.toResponse(reservation)).thenReturn(RESPONSE);

        DeliveryReservationResponse response = adminService.getByOrderId(ORDER_ID);

        assertThat(response).isEqualTo(RESPONSE);
    }

    @Test
    @DisplayName("должен бросить NotFoundException, если резерв по orderId не найден")
    void shouldThrowNotFoundWhenReservationByOrderIdIsMissing() {
        when(deliveryReservationRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> adminService.getByOrderId(ORDER_ID));
    }

    @Test
    @DisplayName("должен бросить NotFoundException, если резерв по id не найден")
    void shouldThrowNotFoundWhenReservationByIdIsMissing() {
        when(deliveryReservationRepository.findById(RESERVATION_ID)).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> adminService.getById(RESERVATION_ID));
    }

    @Test
    @DisplayName("должен вернуть страницу резервов с конвертацией enum-фильтра статуса")
    void shouldReturnFilteredPageOfReservations() {
        DeliveryReservation reservation = DeliveryReservation.builder().orderId(ORDER_ID).build();
        Pageable pageable = PageRequest.of(0, 20);
        Page<DeliveryReservation> page = new PageImpl<>(List.of(reservation), pageable, 1);

        when(deliveryReservationRepository.findWithFilters(
                eq(DATE), eq(DeliveryReservationStatus.RESERVED), isNull(), isNull(), eq(pageable)))
                .thenReturn(page);
        when(deliveryReservationMapper.toResponse(reservation)).thenReturn(RESPONSE);

        Page<DeliveryReservationResponse> result = adminService.getReservations(
                DATE, DeliveryReservationStatusFilter.RESERVED, null, null, pageable);

        assertThat(result.getContent()).containsExactly(RESPONSE);
        assertThat(result.getTotalElements()).isEqualTo(1);
        verify(deliveryReservationRepository).findWithFilters(
                DATE, DeliveryReservationStatus.RESERVED, null, null, pageable);
    }

    @Test
    @DisplayName("должен передать null-статус в репозиторий, если фильтр статуса не задан")
    void shouldPassNullStatusWhenFilterIsNotSet() {
        Pageable pageable = PageRequest.of(0, 20);
        when(deliveryReservationRepository.findWithFilters(
                eq(DATE), isNull(), eq(10L), eq(2), eq(pageable)))
                .thenReturn(Page.empty(pageable));

        Page<DeliveryReservationResponse> result = adminService.getReservations(
                DATE, null, 10L, 2, pageable);

        assertThat(result.getContent()).isEmpty();
        verify(deliveryReservationRepository).findWithFilters(DATE, null, 10L, 2, pageable);
    }
}
