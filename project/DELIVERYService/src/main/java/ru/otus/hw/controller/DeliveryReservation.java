package ru.otus.hw.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ru.otus.hw.dto.DeliveryReservationResponse;
import ru.otus.hw.dto.ErrorDto;

import java.time.LocalDate;

@RequestMapping("/api/v1/delivery")
@Tag(name = "Delivery Reservation API", description = "REST API for viewing delivery reservations")
@SecurityRequirement(name = "basicAuth")
public interface DeliveryReservation {

    /**
     * Статус резерва для фильтрации (например в admin API).
     */
    enum DeliveryReservationStatusFilter {
        RESERVED,
        CONFIRMED,
        CANCELLED,
        FAILED
    }

    /**
     * Получить резерв доставки по id заказа.<br>
     * Например:<br>
     * GET /api/v1/delivery/orders/100/reservation
     */
    @GetMapping("/orders/{orderId}/reservation")
    @Operation(summary = "Get delivery reservation by order ID",
            description = "Retrieves a delivery reservation by the order ID")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Delivery reservation found",
                    content = @Content(schema = @Schema(implementation = DeliveryReservationResponse.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "403", description = "Access denied (not the owner and not ADMIN)",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "404", description = "Delivery reservation not found for the order",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class)))
    })
    ResponseEntity<DeliveryReservationResponse> getByOrderId(
            @PathVariable("orderId") Long orderId
    );

    /**
     * Получить резерв доставки по id самого резерва.<br>
     * Например:<br>
     * GET /api/v1/delivery/reservations/55
     */
    @GetMapping("/reservations/{reservationId}")
    @Operation(summary = "Get delivery reservation by ID",
            description = "Retrieves a delivery reservation by the reservation ID")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Delivery reservation found",
                    content = @Content(schema = @Schema(implementation = DeliveryReservationResponse.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "403", description = "Access denied (not the owner and not ADMIN)",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "404", description = "Delivery reservation not found",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class)))
    })
    ResponseEntity<DeliveryReservationResponse> getById(
            @PathVariable("reservationId") Long reservationId
    );

    /**
     * Получить список резервов доставки с фильтрами и пагинацией.<br>
     * Например:<br>
     * GET /api/v1/delivery/reservations?date=2026-08-01<br>
     * GET /api/v1/delivery/reservations?date=2026-08-01&status=RESERVED<br>
     * GET /api/v1/delivery/reservations?date=2026-08-01&courierSlotId=10<br>
     * GET /api/v1/delivery/reservations?date=2026-08-01&assignedCourierNumber=2<br>
     */
    @GetMapping("/reservations")
    @Operation(summary = "Search delivery reservations with filters",
            description = "Retrieves a paginated list of delivery reservations for a date "
                    + "with optional filters by status, courier slot and assigned courier number")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "List of delivery reservations retrieved"),
            @ApiResponse(responseCode = "400", description = "Invalid date format or missing date parameter",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "Access denied (ADMIN role required)")
    })
    ResponseEntity<Page<DeliveryReservationResponse>> getReservations(
            @RequestParam("date")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate date,

            @RequestParam(value = "status", required = false)
            DeliveryReservationStatusFilter status,

            @RequestParam(value = "courierSlotId", required = false)
            Long courierSlotId,

            @RequestParam(value = "assignedCourierNumber", required = false)
            Integer assignedCourierNumber,

            @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC)
            Pageable pageable
    );
}
