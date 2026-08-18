package ru.otus.hw.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
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
import ru.otus.hw.dto.ErrorDto;
import ru.otus.hw.dto.ProductReservationCreateRequestDto;
import ru.otus.hw.dto.ProductReservationListResponseDto;
import ru.otus.hw.service.ProductReservationService;

@RestController
@RequiredArgsConstructor
@RequestMapping("/internal")
@Tag(name = "Internal Product Reservation API",
        description = "Internal REST API for product reservation (service-to-service, saga orchestration)")
public class InternalProductReservationResource {

    private final ProductReservationService productReservationService;

    @PostMapping("/products/reservations")
    @Operation(summary = "Reserve products for order (internal)",
            description = "Reserves product stock for an order. Idempotent by per-item idempotency key. "
                    + "Called by ORDERService during order processing.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Products reserved",
                    content = @Content(schema = @Schema(implementation = ProductReservationListResponseDto.class))),
            @ApiResponse(responseCode = "400", description = "Validation failed or malformed request",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "404", description = "Product not found",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "409", description = "Not enough stock or reservation conflict",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class)))
    })
    public ResponseEntity<ProductReservationListResponseDto> reserve(
            @Valid @RequestBody ProductReservationCreateRequestDto request) {
        return ResponseEntity.ok(productReservationService.reserve(request));
    }

    @PostMapping("/products/reservations/{orderId}/cancel")
    @Operation(summary = "Cancel reservations for order (internal)",
            description = "Releases reserved stock back for an order (saga compensation). "
                    + "No-op for already released reservations. Reservations in CONFIRMED status are final: "
                    + "409 RESERVATION_ALREADY_CONFIRMED.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Reservations cancelled",
                    content = @Content(schema = @Schema(implementation = ProductReservationListResponseDto.class))),
            @ApiResponse(responseCode = "404", description = "Reservations not found for order",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "409", description = "Reservation already confirmed or conflict",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class)))
    })
    public ResponseEntity<ProductReservationListResponseDto> cancel(@PathVariable Long orderId) {
        return ResponseEntity.ok(productReservationService.cancel(orderId));
    }

    @PostMapping("/products/reservations/{orderId}/confirm")
    @Operation(summary = "Confirm reservations for order (internal)",
            description = "Confirms reservations and writes off reserved stock (saga finalization). "
                    + "No-op for already confirmed reservations.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Reservations confirmed",
                    content = @Content(schema = @Schema(implementation = ProductReservationListResponseDto.class))),
            @ApiResponse(responseCode = "400", description = "Reservation in non-confirmable status",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "404", description = "Reservations not found for order",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "409", description = "Reservation conflict",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class)))
    })
    public ResponseEntity<ProductReservationListResponseDto> confirm(@PathVariable Long orderId) {
        return ResponseEntity.ok(productReservationService.confirm(orderId));
    }

    @GetMapping("/products/reservations/{orderId}")
    @Operation(summary = "Get reservations for order (internal)",
            description = "Retrieves all product reservations for an order with current statuses")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Reservations found",
                    content = @Content(schema = @Schema(implementation = ProductReservationListResponseDto.class))),
            @ApiResponse(responseCode = "404", description = "Reservations not found for order",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class)))
    })
    public ResponseEntity<ProductReservationListResponseDto> getReservation(@PathVariable Long orderId) {
        return ResponseEntity.ok(productReservationService.getByOrderId(orderId));
    }

}
