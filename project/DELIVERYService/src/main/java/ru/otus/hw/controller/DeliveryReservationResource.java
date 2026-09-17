package ru.otus.hw.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.otus.hw.controller.DeliveryReservation.DeliveryReservationStatusFilter;
import ru.otus.hw.dto.DeliveryReservationResponse;
import ru.otus.hw.dto.ErrorDto;
import ru.otus.hw.service.DeliveryReservationService;

import java.time.LocalDate;

/**
 * Публичный просмотр броней доставки.
 *
 * <p>Маршрутные аннотации объявлены на конкретном классе (не на интерфейсе):
 * {@code @EnableMethodSecurity} оборачивает контроллер CGLIB-прокси, при котором
 * {@code @RequestMapping}-методы, унаследованные от интерфейса, перестают обнаруживаться.
 *
 * <p>Конкретная бронь видна её владельцу ({@code delivery_reservations.user_id}) или ADMIN,
 * фильтрованный список (админ-срез) - только ADMIN.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/delivery")
@Tag(name = "Delivery Reservation API", description = "REST API for viewing delivery reservations")
@SecurityRequirement(name = "basicAuth")
public class DeliveryReservationResource {

    private final DeliveryReservationService deliveryReservationService;

    @GetMapping("/orders/{orderId}/reservation")
    @Operation(summary = "Get delivery reservation by order ID",
            description = "Retrieves a delivery reservation by the order ID (owner or ADMIN only)")
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
    @PreAuthorize("@deliveryAuthz.reservationOwnerByOrder(#orderId)")
    public ResponseEntity<DeliveryReservationResponse> getByOrderId(
            @PathVariable("orderId") Long orderId) {
        return ResponseEntity.ok(deliveryReservationService.getByOrderId(orderId));
    }

    @GetMapping("/reservations/{reservationId}")
    @Operation(summary = "Get delivery reservation by ID",
            description = "Retrieves a delivery reservation by the reservation ID (owner or ADMIN only)")
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
    @PreAuthorize("@deliveryAuthz.reservationOwnerById(#reservationId)")
    public ResponseEntity<DeliveryReservationResponse> getById(
            @PathVariable("reservationId") Long reservationId) {
        return ResponseEntity.ok(deliveryReservationService.getById(reservationId));
    }

    @GetMapping("/reservations")
    @Operation(summary = "Search delivery reservations with filters",
            description = "Admin view: retrieves a paginated list of delivery reservations for a date with "
                    + "optional filters by status, courier slot and assigned courier number")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "List of delivery reservations retrieved"),
            @ApiResponse(responseCode = "400", description = "Invalid date format or missing date parameter",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "Access denied (ADMIN role required)")
    })
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Page<DeliveryReservationResponse>> getReservations(
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
            Pageable pageable) {
        return ResponseEntity.ok(deliveryReservationService
                .getReservations(date, status, courierSlotId, assignedCourierNumber, pageable));
    }
}
