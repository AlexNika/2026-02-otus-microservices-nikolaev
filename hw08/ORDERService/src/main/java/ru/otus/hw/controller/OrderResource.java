package ru.otus.hw.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import ru.otus.hw.dto.OrderCreateDto;
import ru.otus.hw.dto.OrderResponseDto;
import ru.otus.hw.service.OrderService;

import java.net.URI;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("api/v1/order")
@Tag(name = "Order API", description = "REST API for order management")
public class OrderResource {

    private final OrderService orderService;

    @PostMapping
    @Operation(summary = "Create a new order",
            description = "Creates a new order and runs the orchestration saga: withdraw funds via BILLINGService, "
                    + "reserve the product via WAREHOUSEService, reserve a delivery slot via DELIVERYService, "
                    + "then confirm the reservations. If any step fails, all completed steps are compensated "
                    + "in reverse order and the order is marked FAILED.")
    public ResponseEntity<OrderResponseDto> createOrder(@Valid @RequestBody OrderCreateDto orderCreateDto) {
        OrderResponseDto response = orderService.createOrder(orderCreateDto);
        URI location = ServletUriComponentsBuilder
                .fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(response.id())
                .toUri();
        return ResponseEntity.created(location).body(response);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get order by ID",
            description = "Retrieves an order by its ID")
    public ResponseEntity<OrderResponseDto> getOrderById(@PathVariable Long id) {
        return ResponseEntity.ok(orderService.getOrderById(id));
    }

    @GetMapping("/user/{userId}")
    @Operation(summary = "Get orders by user ID",
            description = "Retrieves all orders for a specific user")
    public ResponseEntity<List<OrderResponseDto>> getOrdersByUserId(@PathVariable Long userId) {
        return ResponseEntity.ok(orderService.getOrderByUserId(userId));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel an order",
            description = "Cancels a placed order: refunds funds via BILLINGService and releases the warehouse and "
                    + "delivery reservations. Note: after a successful saga the reservations are CONFIRMED and final, "
                    + "so cancelling such an order returns 409 ORDER_STATE_CONFLICT and the order stays PLACED.")
    public ResponseEntity<OrderResponseDto> cancelOrder(@PathVariable Long id) {
        return ResponseEntity.ok(orderService.cancelOrder(id));
    }
}
