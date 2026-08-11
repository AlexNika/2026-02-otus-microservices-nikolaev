package ru.otus.hw.service;

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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static ru.otus.hw.dto.ReservationStatus.RESERVED;

@ExtendWith(MockitoExtension.class)
class ProductReservationServiceImplTest {

    private static final Long ORDER_ID = 100L;

    private static final Long PRODUCT_ID = 1L;

    private static final Long IDEMPOTENCY_KEY = 42L;

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
}
