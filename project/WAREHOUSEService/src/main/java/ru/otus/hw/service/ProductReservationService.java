package ru.otus.hw.service;

import ru.otus.hw.dto.ProductReservationCreateRequestDto;
import ru.otus.hw.dto.ProductReservationListResponseDto;

import java.util.Optional;

public interface ProductReservationService {

    ProductReservationListResponseDto reserve(ProductReservationCreateRequestDto request);

    ProductReservationListResponseDto cancel(Long orderId);

    ProductReservationListResponseDto confirm(Long orderId);

    Optional<ProductReservationListResponseDto> findByOrderId(Long orderId);

    ProductReservationListResponseDto getByOrderId(Long orderId);

}
