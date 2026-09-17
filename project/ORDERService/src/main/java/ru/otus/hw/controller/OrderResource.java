package ru.otus.hw.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PostAuthorize;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import ru.otus.hw.dto.ErrorDto;
import ru.otus.hw.dto.OrderCreateDto;
import ru.otus.hw.dto.OrderCreateResult;
import ru.otus.hw.dto.OrderResponseDto;
import ru.otus.hw.exception.IdempotencyKeyFormatException;
import ru.otus.hw.security.AuthPrincipal;
import ru.otus.hw.service.OrderService;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Публичный API заказов: доступен по JWT. Владелец заказа - {@code userId} из claims токена
 * (поле в теле игнорируется); чтение/отмена чужих заказов - только ADMIN.
 * Помимо JWT-токена поддерживается HTTP Basic (email + пароль) - только для отладки
 * в Swagger-UI (валидация через запущенный AUTHService, см. {@code OpenApiConfig}).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("api/v1/order")
@Tag(name = "Order API", description = "REST API for order management")
@SecurityRequirement(name = "basicAuth")
public class OrderResource {

    private final OrderService orderService;

    @PostMapping
    @Operation(summary = "Create a new order",
            description = "Creates a new order for the authenticated user (userId is taken from the JWT, "
                    + "not from the request body) and runs the orchestration saga: withdraw funds via "
                    + "BILLINGService, reserve the product via WAREHOUSEService, reserve a delivery slot via "
                    + "DELIVERYService, then confirm the reservations. If any step fails, all completed steps are "
                    + "compensated in reverse order and the order is marked FAILED. "
                    + "Supports an optional Idempotency-Key header (UUID): repeating a request with the same key "
                    + "and payload never creates a second order. A key reused with a different payload returns "
                    + "409 IDEMPOTENCY_KEY_CONFLICT; a malformed key returns 400 MALFORMED_REQUEST.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Order created and the saga completed successfully",
                    content = @Content(schema = @Schema(implementation = OrderResponseDto.class))),
            @ApiResponse(responseCode = "200",
                    description = "Idempotent replay: the same Idempotency-Key was already used, "
                            + "current or stored state of the order is returned without creating a duplicate",
                    content = @Content(schema = @Schema(implementation = OrderResponseDto.class))),
            @ApiResponse(responseCode = "400",
                    description = "Validation failed or malformed Idempotency-Key header (MALFORMED_REQUEST)",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "402", description = "Insufficient funds on the billing account",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "409",
                    description = "Idempotency-Key reused with a different payload (IDEMPOTENCY_KEY_CONFLICT) "
                            + "or saga step state conflict",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "502", description = "Downstream service (BILLING/WAREHOUSE/DELIVERY) failed",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "503",
                    description = "Downstream circuit breaker is open or the service is rate limited "
                            + "(Retry-After header is set for CIRCUIT_BREAKER_OPEN)",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class)))
    })
    public ResponseEntity<OrderResponseDto> createOrder(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKeyHeader,
            @Valid @RequestBody OrderCreateDto orderCreateDto) {
        UUID idempotencyKey = parseIdempotencyKey(idempotencyKeyHeader);

        OrderCreateResult result = orderService.createOrder(orderCreateDto, principal.userId(), idempotencyKey);
        OrderResponseDto response = result.order();
        URI location = ServletUriComponentsBuilder
                .fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(response.id())
                .toUri();

        if (result.kind() == OrderCreateResult.Kind.REPLAYED_CURRENT) {
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.created(location).body(response);
    }

    private @Nullable UUID parseIdempotencyKey(String headerValue) {
        if (!StringUtils.hasText(headerValue)) {
            return null;
        }
        try {
            return UUID.fromString(headerValue.trim());
        } catch (IllegalArgumentException e) {
            throw new IdempotencyKeyFormatException(headerValue);
        }
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get order by ID",
            description = "Retrieves an order by its ID; only the owner or ADMIN")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Order found",
                    content = @Content(schema = @Schema(implementation = OrderResponseDto.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "403", description = "Access denied (not the owner and not ADMIN)",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "404", description = "Order not found",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class)))
    })
    @PostAuthorize("@authz.ownerOrAdmin(returnObject?.body?.userId())")
    public ResponseEntity<OrderResponseDto> getOrderById(@PathVariable Long id) {
        return ResponseEntity.ok(orderService.getOrderById(id));
    }

    @GetMapping("/user/{userId}")
    @Operation(summary = "Get orders by user ID",
            description = "Retrieves all orders for a specific user; only self or ADMIN")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Orders of the user (possibly empty list)",
                    content = @Content(array = @ArraySchema(
                            schema = @Schema(implementation = OrderResponseDto.class)))),
            @ApiResponse(responseCode = "401", description = "Authentication required",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "403", description = "Access denied (not self and not ADMIN)",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class)))
    })
    @PreAuthorize("@authz.ownerOrAdmin(#userId)")
    public ResponseEntity<List<OrderResponseDto>> getOrdersByUserId(@PathVariable Long userId) {
        return ResponseEntity.ok(orderService.getOrderByUserId(userId));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel an order",
            description = "Cancels a placed order (owner or ADMIN only): refunds funds via BILLINGService and "
                    + "releases the warehouse and delivery reservations. Note: after a successful saga the "
                    + "reservations are CONFIRMED and final, so cancelling such an order returns "
                    + "409 ORDER_STATE_CONFLICT and the order stays PLACED.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Order cancelled (status CANCELED)",
                    content = @Content(schema = @Schema(implementation = OrderResponseDto.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "403", description = "Access denied (not the owner)",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "404", description = "Order not found",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "409",
                    description = "Order state conflict (ORDER_STATE_CONFLICT): only PLACED orders can be "
                            + "cancelled and reservations confirmed by a successful saga are final",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "502", description = "Downstream service (BILLING/WAREHOUSE/DELIVERY) failed",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "503",
                    description = "Downstream circuit breaker is open or the service is rate limited "
                            + "(Retry-After header is set for CIRCUIT_BREAKER_OPEN)",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class)))
    })
    @PreAuthorize("@orderAuthz.orderOwner(#id)")
    public ResponseEntity<OrderResponseDto> cancelOrder(@PathVariable Long id) {
        return ResponseEntity.ok(orderService.cancelOrder(id));
    }
}
