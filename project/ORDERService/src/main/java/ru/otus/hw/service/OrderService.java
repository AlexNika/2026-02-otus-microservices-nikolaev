package ru.otus.hw.service;

import ru.otus.hw.dto.OrderCreateDto;
import ru.otus.hw.dto.OrderCreateResult;
import ru.otus.hw.dto.OrderResponseDto;
import ru.otus.hw.models.Order;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderService {

    Optional<OrderResponseDto> findOrderById(Long id);

    OrderResponseDto getOrderById(Long id);

    List<OrderResponseDto> getOrderByOrderStatus(Order.OrderStatus orderStatus);

    List<OrderResponseDto> getAllOrders();

    List<OrderResponseDto> getOrderByUserId(Long userId);

    OrderResponseDto createOrder(OrderCreateDto orderCreateDto, Long userId);

    /**
     * Создание заказа с опциональным Idempotency-Key ({@code null} - поведение без ключа,
     * обратная совместимость). Повтор ключа с тем же payload'ом никогда не создаёт второй заказ.
     * {@code userId} берётся из JWT ( AuthPrincipal), а не из тела запроса.
     */
    OrderCreateResult createOrder(OrderCreateDto orderCreateDto, Long userId, UUID idempotencyKey);

    OrderResponseDto cancelOrder(Long orderId);

}
