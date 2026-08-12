package ru.otus.hw.dto.mapper;

import org.jspecify.annotations.NonNull;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;
import ru.otus.hw.dto.ProductReservationListResponseDto;
import ru.otus.hw.dto.ProductReservationResponseDto;
import ru.otus.hw.model.Product;
import ru.otus.hw.model.ProductReservation;

import java.util.List;

@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE, componentModel = MappingConstants.ComponentModel.SPRING)
public interface ProductReservationMapper {

    default ProductReservationResponseDto toProductReservationResponseDto(@NonNull ProductReservation reservation) {
        Product product = reservation.getProduct();
        return ProductReservationResponseDto.builder()
                .id(reservation.getId())
                .orderId(reservation.getOrderId())
                .productId(product != null ? product.getId() : null)
                .sku(product != null ? product.getSku() : null)
                .quantity(reservation.getQuantity())
                .reservationStatus(reservation.getReservationStatus())
                .idempotencyKey(reservation.getIdempotencyKey())
                .version(reservation.getVersion())
                .created(reservation.getCreated())
                .updated(reservation.getUpdated())
                .build();
    }

    default ProductReservationListResponseDto toListResponseDto(Long orderId,
                                                                @NonNull List<ProductReservation> reservations) {
        return ProductReservationListResponseDto.builder()
                .orderId(orderId)
                .reservations(reservations.stream()
                        .map(this::toProductReservationResponseDto)
                        .toList())
                .build();
    }

    default ProductReservation toEntity(Long orderId, Integer quantity, Long idempotencyKey) {
        return ProductReservation.builder()
                .orderId(orderId)
                .quantity(quantity)
                .idempotencyKey(idempotencyKey)
                .build();
    }

}
