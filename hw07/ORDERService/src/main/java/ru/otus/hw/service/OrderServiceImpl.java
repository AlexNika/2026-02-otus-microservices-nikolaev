package ru.otus.hw.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.otus.hw.client.BillingServiceClient;
import ru.otus.hw.dto.NotificationEvent;
import ru.otus.hw.dto.OrderCreateDto;
import ru.otus.hw.dto.OrderResponseDto;
import ru.otus.hw.dto.mapper.OrderMapper;
import ru.otus.hw.exception.BillingServiceException;
import ru.otus.hw.exception.NotFoundException;
import ru.otus.hw.models.Order;
import ru.otus.hw.producer.NotificationEventPublisher;
import ru.otus.hw.repository.OrderRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;

    private final OrderMapper orderMapper;

    private final BillingServiceClient billingServiceClient;

    private final NotificationEventPublisher notificationEventPublisher;

    @Override
    @Transactional(readOnly = true)
    public Optional<OrderResponseDto> findById(Long id) {
        log.debug("Searching for order with id: {}", id);
        return orderRepository.findById(id)
                .map(order -> {
                    log.info("Order with id: {} found", id);
                    return orderMapper.toOrderResponseDto(order);
                });
    }

    @Override
    @Transactional(readOnly = true)
    public OrderResponseDto getById(Long id) {
        return findById(id)
                .orElseThrow(() -> new NotFoundException("Order with id " + id + " not found"));
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderResponseDto> getAllById() {
        log.debug("Fetching all orders");
        return orderRepository.findAll()
                .stream()
                .map(orderMapper::toOrderResponseDto)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderResponseDto> getByUserId(Long userId) {
        log.debug("Fetching all orders for userId: {}", userId);
        return orderRepository.findByUserId(userId)
                .stream()
                .map(orderMapper::toOrderResponseDto)
                .toList();
    }

    @Override
    @Transactional
    public OrderResponseDto createOrder(@NonNull OrderCreateDto orderCreateDto) {
        log.info("Creating order for userId: {}, price: {}, description: {}", 
                orderCreateDto.userId(), orderCreateDto.price(), orderCreateDto.description());

        Order order = orderMapper.toEntity(orderCreateDto);
        order.setOrderStatus(Order.OrderStatus.PENDING);
        Order savedOrder = orderRepository.save(order);
        log.info("Order created with PENDING status, id: {}", savedOrder.getId());

        savedOrder.setOrderStatus(Order.OrderStatus.PROCESSING);
        orderRepository.save(savedOrder);
        log.info("Order status changed to PROCESSING, id: {}", savedOrder.getId());
        
        try {
            billingServiceClient.withdrawFunds(
                    savedOrder.getUserId(),
                    savedOrder.getPrice(),
                    savedOrder.getId()
            );
            log.info("BillingService withdrawal successful for order id: {}", savedOrder.getId());

            savedOrder.setOrderStatus(Order.OrderStatus.PLACED);
            orderRepository.save(savedOrder);
            log.info("Order status changed to PLACED, id: {}", savedOrder.getId());

            notificationEventPublisher.send(buildNotificationEvent(
                    savedOrder, "Order placed successfully. Payment confirmed."));

            return orderMapper.toOrderResponseDto(savedOrder);

        } catch (BillingServiceException e) {
            log.error("BillingService error for order id: {}, changing status to FAILED", savedOrder.getId());
            savedOrder.setOrderStatus(Order.OrderStatus.FAILED);
            orderRepository.save(savedOrder);

            notificationEventPublisher.send(buildNotificationEvent(
                    savedOrder, "Order payment failed: " + e.getMessage()));

            throw e;
        }
    }

    @Override
    @Transactional
    public OrderResponseDto cancelOrder(Long orderId) {
        log.info("Cancelling order with id: {}", orderId);

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new NotFoundException("Order not found with id: " + orderId));
        log.debug("Order with id: {} found, current status: {}", orderId, order.getOrderStatus());

        if (order.getOrderStatus() != Order.OrderStatus.PLACED) {
            String message = "Only PLACED orders can be canceled, current status: " + order.getOrderStatus();
            log.warn(message);
            throw new IllegalStateException(message);
        }

        billingServiceClient.refundFunds(
                order.getUserId(),
                order.getPrice(),
                order.getId()
        );
        log.info("BillingService refund successful for order id: {}", orderId);

        order.setOrderStatus(Order.OrderStatus.CANCELED);
        orderRepository.save(order);
        log.info("Order status changed to CANCELED, id: {}", orderId);
        
        return orderMapper.toOrderResponseDto(order);
    }

    private NotificationEvent buildNotificationEvent(@NonNull Order order, String message) {
        return NotificationEvent.builder()
                .orderId(order.getId())
                .userId(order.getUserId())
                .price(order.getPrice())
                .status(order.getOrderStatus().name())
                .message(message)
                .timestamp(Instant.now())
                .build();
    }
}
