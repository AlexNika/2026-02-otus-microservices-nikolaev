package ru.otus.hw.service;

import ru.otus.hw.dto.OrderCreateDto;
import ru.otus.hw.dto.OrderResponseDto;

import java.util.List;
import java.util.Optional;

public interface OrderService {

    Optional<OrderResponseDto> findById(Long id);

    OrderResponseDto getById(Long id);

    List<OrderResponseDto> getAllById();

    List<OrderResponseDto> getByUserId(Long userId);

    OrderResponseDto createOrder(OrderCreateDto orderCreateDto);

    OrderResponseDto cancelOrder(Long orderId);

}
