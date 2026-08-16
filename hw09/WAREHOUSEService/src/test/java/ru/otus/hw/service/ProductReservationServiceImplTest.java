package ru.otus.hw.service;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.otus.hw.dto.ProductReservationCreateRequestDto;
import ru.otus.hw.dto.ProductReservationListResponseDto;
import ru.otus.hw.dto.ReservationItemRequestDto;
import ru.otus.hw.dto.mapper.ProductReservationMapper;
import ru.otus.hw.exception.ErrorCodes;
import ru.otus.hw.exception.ProductReservationException;
import ru.otus.hw.model.Product;
import ru.otus.hw.model.ProductReservation;
import ru.otus.hw.model.ProductStock;
import ru.otus.hw.repository.ProductRepository;
import ru.otus.hw.repository.ProductReservationRepository;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static ru.otus.hw.dto.ReservationStatus.RESERVED;

@ExtendWith(MockitoExtension.class)
class ProductReservationServiceImplTest {

    private static final Long ORDER_ID = 100L;

    private static final Long PRODUCT_ID = 1L;

    private static final String IDEMPOTENCY_KEY = "order-" + ORDER_ID + "-p" + PRODUCT_ID;

    @Mock
    private ProductReservationRepository productReservationRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private ProductReservationMapper productReservationMapper;

    @InjectMocks
    private ProductReservationServiceImpl productReservationService;

    @Test
    @DisplayName("должен бросить ProductReservationException с кодом INSUFFICIENT_STOCK при недостатке стока")
    void shouldThrowProductReservationExceptionWhenStockIsInsufficient() {
        ProductStock stock = mock(ProductStock.class);
        when(stock.getAvailableQuantity()).thenReturn(2);
        Product product = mock(Product.class);
        when(product.getProductStock()).thenReturn(stock);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));

        var request = new ProductReservationCreateRequestDto(ORDER_ID,
                List.of(new ReservationItemRequestDto(PRODUCT_ID, 5, IDEMPOTENCY_KEY)));

        ProductReservationException ex = assertThrows(ProductReservationException.class,
                () -> productReservationService.reserve(request));

        assertThat(ex.getCode()).isEqualTo(ErrorCodes.INSUFFICIENT_STOCK);
        assertThat(ex.getProductId()).isEqualTo(PRODUCT_ID);
        assertThat(ex.getRequestedQuantity()).isEqualTo(5);
        assertThat(ex.getAvailableQuantity()).isEqualTo(2);
        assertThat(ex.getMessage())
                .isEqualTo("Not enough stock for product id: 1, requested: 5, available: 2");
        verify(productReservationRepository, never()).save(any());
        verify(stock, never()).setAvailableQuantity(anyInt());
        verify(stock, never()).setReservedQuantity(anyInt());
    }

    @Test
    @DisplayName("должен бросить ProductReservationException с availableQuantity = 0, если сток отсутствует")
    void shouldThrowProductReservationExceptionWhenStockIsNull() {
        Product product = mock(Product.class);
        when(product.getProductStock()).thenReturn(null);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));

        var request = new ProductReservationCreateRequestDto(ORDER_ID,
                List.of(new ReservationItemRequestDto(PRODUCT_ID, 5, IDEMPOTENCY_KEY)));

        ProductReservationException ex = assertThrows(ProductReservationException.class,
                () -> productReservationService.reserve(request));

        assertThat(ex.getCode()).isEqualTo(ErrorCodes.INSUFFICIENT_STOCK);
        assertThat(ex.getProductId()).isEqualTo(PRODUCT_ID);
        assertThat(ex.getRequestedQuantity()).isEqualTo(5);
        assertThat(ex.getAvailableQuantity()).isEqualTo(0);
        assertThat(ex.getMessage())
                .isEqualTo("Not enough stock for product id: 1, requested: 5, available: 0");
        verify(productReservationRepository, never()).save(any());
    }

    @Test
    @DisplayName("должен обновить сток продукта и сохранить резервацию со статусом RESERVED при успешном резерве")
    void shouldUpdateProductStockAndSaveReservationOnSuccessfulReserve() {
        ProductStock stock = mock(ProductStock.class);
        when(stock.getAvailableQuantity()).thenReturn(10);
        when(stock.getReservedQuantity()).thenReturn(2);
        Product product = mock(Product.class);
        when(product.getProductStock()).thenReturn(stock);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));

        ProductReservation reservation = ProductReservation.builder()
                .orderId(ORDER_ID)
                .quantity(3)
                .idempotencyKey(IDEMPOTENCY_KEY)
                .build();
        when(productReservationMapper.toEntity(ORDER_ID, 3, IDEMPOTENCY_KEY)).thenReturn(reservation);
        when(productReservationRepository.save(any(ProductReservation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        ProductReservationListResponseDto responseDto =
                new ProductReservationListResponseDto(ORDER_ID, List.of());
        when(productReservationMapper.toListResponseDto(any(), any())).thenReturn(responseDto);

        var request = new ProductReservationCreateRequestDto(ORDER_ID,
                List.of(new ReservationItemRequestDto(PRODUCT_ID, 3, IDEMPOTENCY_KEY)));

        ProductReservationListResponseDto response = productReservationService.reserve(request);

        verify(stock).setAvailableQuantity(7);
        verify(stock).setReservedQuantity(5);

        ArgumentCaptor<ProductReservation> reservationCaptor = ArgumentCaptor.forClass(ProductReservation.class);
        verify(productReservationRepository).save(reservationCaptor.capture());
        ProductReservation savedReservation = reservationCaptor.getValue();
        assertThat(savedReservation.getReservationStatus()).isEqualTo(RESERVED);
        assertThat(savedReservation.getProduct()).isSameAs(product);
        assertThat(savedReservation.getOrderId()).isEqualTo(ORDER_ID);
        assertThat(savedReservation.getQuantity()).isEqualTo(3);
        assertThat(savedReservation.getIdempotencyKey()).isEqualTo(IDEMPOTENCY_KEY);

        assertThat(response).isSameAs(responseDto);
    }

    @Test
    @DisplayName("replay: тот же ключ и тот же payload - возвращается существующая бронь без новой")
    void shouldReplayExistingReservationWhenSameKeyAndPayload() {
        Product product = mock(Product.class);
        when(product.getId()).thenReturn(PRODUCT_ID);
        ProductReservation existing = ProductReservation.builder()
                .orderId(ORDER_ID)
                .quantity(3)
                .idempotencyKey(IDEMPOTENCY_KEY)
                .build();
        existing.setProduct(product);
        existing.setReservationStatus(RESERVED);
        when(productReservationRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.of(existing));
        ProductReservationListResponseDto responseDto =
                new ProductReservationListResponseDto(ORDER_ID, List.of());
        when(productReservationMapper.toListResponseDto(any(), any())).thenReturn(responseDto);

        var request = new ProductReservationCreateRequestDto(ORDER_ID,
                List.of(new ReservationItemRequestDto(PRODUCT_ID, 3, IDEMPOTENCY_KEY)));

        ProductReservationListResponseDto response = productReservationService.reserve(request);

        assertThat(response).isSameAs(responseDto);
        verify(productReservationRepository, never()).save(any());
        verify(productRepository, never()).findById(any());
    }

    @Test
    @DisplayName("replay: тот же ключ, но другой productId - 409 IDEMPOTENCY_KEY_CONFLICT")
    void shouldThrowConflictWhenSameKeyButDifferentProduct() {
        Product product = mock(Product.class);
        when(product.getId()).thenReturn(999L);
        ProductReservation existing = ProductReservation.builder()
                .orderId(ORDER_ID)
                .quantity(3)
                .idempotencyKey(IDEMPOTENCY_KEY)
                .build();
        existing.setProduct(product);
        existing.setReservationStatus(RESERVED);
        when(productReservationRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.of(existing));

        var request = new ProductReservationCreateRequestDto(ORDER_ID,
                List.of(new ReservationItemRequestDto(PRODUCT_ID, 3, IDEMPOTENCY_KEY)));

        ProductReservationException ex = assertThrows(ProductReservationException.class,
                () -> productReservationService.reserve(request));

        assertThat(ex.getCode()).isEqualTo(ErrorCodes.IDEMPOTENCY_KEY_CONFLICT);
        verify(productReservationRepository, never()).save(any());
    }

    @Test
    @DisplayName("replay: тот же ключ, но другое количество - 409 IDEMPOTENCY_KEY_CONFLICT")
    void shouldThrowConflictWhenSameKeyButDifferentQuantity() {
        Product product = mock(Product.class);
        when(product.getId()).thenReturn(PRODUCT_ID);
        ProductReservation existing = ProductReservation.builder()
                .orderId(ORDER_ID)
                .quantity(5)
                .idempotencyKey(IDEMPOTENCY_KEY)
                .build();
        existing.setProduct(product);
        existing.setReservationStatus(RESERVED);
        when(productReservationRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.of(existing));

        var request = new ProductReservationCreateRequestDto(ORDER_ID,
                List.of(new ReservationItemRequestDto(PRODUCT_ID, 3, IDEMPOTENCY_KEY)));

        ProductReservationException ex = assertThrows(ProductReservationException.class,
                () -> productReservationService.reserve(request));

        assertThat(ex.getCode()).isEqualTo(ErrorCodes.IDEMPOTENCY_KEY_CONFLICT);
        verify(productReservationRepository, never()).save(any());
    }

    @Test
    @DisplayName("агрегация: дубль productId с разными ключами - 400 IllegalArgumentException")
    void shouldThrowBadRequestWhenDuplicateProductWithDifferentKeys() {
        var request = new ProductReservationCreateRequestDto(ORDER_ID,
                List.of(new ReservationItemRequestDto(PRODUCT_ID, 2, "key-a"),
                        new ReservationItemRequestDto(PRODUCT_ID, 3, "key-b")));

        assertThrows(IllegalArgumentException.class, () -> productReservationService.reserve(request));

        verify(productReservationRepository, never()).save(any());
    }

    @Test
    @DisplayName("агрегация: дубль productId с одинаковыми ключами - количества суммируются")
    void shouldAggregateQuantitiesWhenDuplicateProductWithSameKey() {
        ProductStock stock = mock(ProductStock.class);
        when(stock.getAvailableQuantity()).thenReturn(10);
        when(stock.getReservedQuantity()).thenReturn(0);
        Product product = mock(Product.class);
        when(product.getProductStock()).thenReturn(stock);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));

        ProductReservation reservation = ProductReservation.builder()
                .orderId(ORDER_ID)
                .quantity(5)
                .idempotencyKey(IDEMPOTENCY_KEY)
                .build();
        when(productReservationMapper.toEntity(ORDER_ID, 5, IDEMPOTENCY_KEY)).thenReturn(reservation);
        when(productReservationRepository.save(any(ProductReservation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        ProductReservationListResponseDto responseDto =
                new ProductReservationListResponseDto(ORDER_ID, List.of());
        when(productReservationMapper.toListResponseDto(any(), any())).thenReturn(responseDto);

        var request = new ProductReservationCreateRequestDto(ORDER_ID,
                List.of(new ReservationItemRequestDto(PRODUCT_ID, 2, IDEMPOTENCY_KEY),
                        new ReservationItemRequestDto(PRODUCT_ID, 3, IDEMPOTENCY_KEY)));

        productReservationService.reserve(request);

        verify(stock).setAvailableQuantity(5);
        verify(stock).setReservedQuantity(5);
        ArgumentCaptor<ProductReservation> reservationCaptor = ArgumentCaptor.forClass(ProductReservation.class);
        verify(productReservationRepository).save(reservationCaptor.capture());
        assertThat(reservationCaptor.getValue().getQuantity()).isEqualTo(5);
    }

    private @NonNull ProductReservation reservationWithStatus(ru.otus.hw.dto.ReservationStatus status,
                                                              int quantity) {
        Product product = mock(Product.class);
        ProductReservation reservation = ProductReservation.builder()
                .orderId(ORDER_ID)
                .quantity(quantity)
                .idempotencyKey(IDEMPOTENCY_KEY)
                .reservationStatus(status)
                .build();
        reservation.setId(7L);
        reservation.setProduct(product);
        return reservation;
    }

    @Test
    @DisplayName("cancel: RESERVED освобождает сток и переводит бронь в RELEASED")
    void shouldReleaseReservedReservationOnCancel() {
        ProductReservation reservation = reservationWithStatus(RESERVED, 3);
        ProductStock stock = mock(ProductStock.class);
        when(stock.getAvailableQuantity()).thenReturn(10);
        when(stock.getReservedQuantity()).thenReturn(3);
        when(reservation.getProduct().getProductStock()).thenReturn(stock);
        when(productReservationRepository.findByOrderId(ORDER_ID)).thenReturn(List.of(reservation));
        ProductReservationListResponseDto responseDto = new ProductReservationListResponseDto(ORDER_ID, List.of());
        when(productReservationMapper.toListResponseDto(eq(ORDER_ID), any())).thenReturn(responseDto);

        ProductReservationListResponseDto response = productReservationService.cancel(ORDER_ID);

        assertThat(response).isSameAs(responseDto);
        assertThat(reservation.getReservationStatus()).isEqualTo(ru.otus.hw.dto.ReservationStatus.RELEASED);
        verify(stock).setAvailableQuantity(13);
        verify(stock).setReservedQuantity(0);
        verify(productReservationRepository).saveAll(List.of(reservation));
    }

    @Test
    @DisplayName("cancel: уже RELEASED - no-op без изменений")
    void shouldNoOpWhenCancelReleasedReservation() {
        ProductReservation reservation = reservationWithStatus(ru.otus.hw.dto.ReservationStatus.RELEASED, 3);
        when(productReservationRepository.findByOrderId(ORDER_ID)).thenReturn(List.of(reservation));
        ProductReservationListResponseDto responseDto = new ProductReservationListResponseDto(ORDER_ID, List.of());
        when(productReservationMapper.toListResponseDto(eq(ORDER_ID), any())).thenReturn(responseDto);

        productReservationService.cancel(ORDER_ID);

        assertThat(reservation.getReservationStatus()).isEqualTo(ru.otus.hw.dto.ReservationStatus.RELEASED);
        verify(productReservationRepository).saveAll(List.of(reservation));
    }

    @Test
    @DisplayName("cancel: CONFIRMED - 409 с машинным кодом RESERVATION_ALREADY_CONFIRMED")
    void shouldThrowConflictWhenCancelConfirmedReservation() {
        ProductReservation reservation = reservationWithStatus(ru.otus.hw.dto.ReservationStatus.CONFIRMED, 3);
        when(productReservationRepository.findByOrderId(ORDER_ID)).thenReturn(List.of(reservation));

        ProductReservationException ex = assertThrows(ProductReservationException.class,
                () -> productReservationService.cancel(ORDER_ID));

        assertThat(ex.getCode()).isEqualTo(ErrorCodes.RESERVATION_ALREADY_CONFIRMED);
        assertThat(ex.getMessage()).contains("CONFIRMED");
        verify(productReservationRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("cancel: бронь не найдена - NotFoundException")
    void shouldThrowNotFoundWhenCancelWithoutReservations() {
        when(productReservationRepository.findByOrderId(ORDER_ID)).thenReturn(List.of());

        assertThrows(ru.otus.hw.exception.NotFoundException.class,
                () -> productReservationService.cancel(ORDER_ID));
    }
}
