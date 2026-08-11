package ru.otus.hw.service;

import ru.otus.hw.dto.OrderCreateDto;
import ru.otus.hw.dto.OrderResponseDto;

import java.util.List;
import java.util.Optional;

public interface OrderService {

    Optional<OrderResponseDto> findOrderById(Long id);

    OrderResponseDto getOrderById(Long id);

    List<OrderResponseDto> getAllOrders();

    List<OrderResponseDto> getOrderByUserId(Long userId);

    OrderResponseDto createOrder(OrderCreateDto orderCreateDto);

    OrderResponseDto cancelOrder(Long orderId);

}
