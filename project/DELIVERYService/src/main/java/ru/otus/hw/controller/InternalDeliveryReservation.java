package ru.otus.hw.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import ru.otus.hw.dto.CancelDeliveryResponse;
import ru.otus.hw.dto.DeliveryReservationResponse;
import ru.otus.hw.dto.ErrorDto;
import ru.otus.hw.dto.ReserveDeliveryRequest;

@RequestMapping("/internal/delivery/reservations")
@Tag(name = "Internal Delivery Reservation API",
        description = "Internal REST API for delivery reservation (service-to-service, saga orchestration)")
public interface InternalDeliveryReservation {

    /**
     *     Прямой шаг саги. Резервирует доставку для заказа на конкретную дату и временной слот.
     *     Сервис сам выбирает свободный номер курьера.<br>
     *     Коды ошибок:<br>
     *     `201` - резерв создан впервые;<br>
     *     `200` - идемпотентный повторный вызов, резерв уже существует;<br>
     *     `400` - ошибка валидации запроса, например слот некорректен;<br>
     *     `404` - дата или слот не настроены;<br>
     *     `409` - нет свободного курьера, слот заполнен или конфликт состояния резерва.
     *
     * @param request ReserveDeliveryRequest dto
     * @return ResponseEntity with DeliveryReservationResponse dto
     */
    @PostMapping
    @Operation(summary = "Reserve delivery for order (internal)",
            description = "Saga forward step. Reserves delivery for an order on a specific date and time slot. "
                    + "The service picks a free courier number itself. Idempotent by order ID. "
                    + "Called by ORDERService during order processing.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Delivery reservation created",
                    content = @Content(schema = @Schema(implementation = DeliveryReservationResponse.class))),
            @ApiResponse(responseCode = "200", description = "Idempotent repeated call, reservation already exists",
                    content = @Content(schema = @Schema(implementation = DeliveryReservationResponse.class))),
            @ApiResponse(responseCode = "400", description = "Validation failed, e.g. invalid slot",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "404", description = "Date or slot is not configured",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "409",
                    description = "No free courier, slot is full or reservation state conflict",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class)))
    })
    ResponseEntity<DeliveryReservationResponse> reserve(
            @Valid @RequestBody ReserveDeliveryRequest request
    );


    /**
     * Получение текущего состояния резерва по `orderId`.
     * Используется `OrderService` для проверки статуса после тайм-аутов и принятия решения о продолжении саги или компенсации.<br>
     * Коды ошибок:<br>
     * `404` - резерв не найден.
     *
     * @param orderId Long orderId
     * @return ResponseEntity DeliveryReservationResponse dto
     */
    @GetMapping("/{orderId}")
    @Operation(summary = "Get delivery reservation by order ID (internal)",
            description = "Retrieves the current reservation state by order ID. "
                    + "Used by ORDERService to check the status after timeouts "
                    + "and decide whether to continue the saga or compensate.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Delivery reservation found",
                    content = @Content(schema = @Schema(implementation = DeliveryReservationResponse.class))),
            @ApiResponse(responseCode = "404", description = "Delivery reservation not found for the order",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class)))
    })
    ResponseEntity<DeliveryReservationResponse> getByOrderId(
            @PathVariable Long orderId
    );

    /**
     * Получение текущего состояния резерва по `reservationId`.<br>
     * Например:<br>
     * GET /internal/delivery/reservations/by-id/55<br>
     * Коды ошибок:<br>
     * `404` - резерв не найден.
     *
     * @param reservationId Long reservationId
     * @return ResponseEntity DeliveryReservationResponse dto
     */
    @GetMapping("/by-id/{reservationId}")
    @Operation(summary = "Get delivery reservation by reservation ID (internal)",
            description = "Retrieves the current reservation state by the reservation ID")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Delivery reservation found",
                    content = @Content(schema = @Schema(implementation = DeliveryReservationResponse.class))),
            @ApiResponse(responseCode = "404", description = "Delivery reservation not found",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class)))
    })
    ResponseEntity<DeliveryReservationResponse> getById(
            @PathVariable Long reservationId
    );


    /**
     * Подтверждение резерва после успешного завершения саги или другого бизнес-процесса. Метод идемпотентный.<br>
     * Коды ошибок:<br>
     * `200` - резерв подтверждён или уже был подтверждён;<br>
     * `404` - резерв не найден;<br>
     * `409` - резерв отменён, подтверждение невозможно.
     *
     * @param orderId Long orderId
     * @return ResponseEntity DeliveryReservationResponse dto
     */
    @PostMapping("/{orderId}/confirm")
    @Operation(summary = "Confirm delivery reservation for order (internal)",
            description = "Confirms the delivery reservation after successful saga finalization. "
                    + "Idempotent: no-op for already confirmed reservations.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Reservation confirmed or was already confirmed",
                    content = @Content(schema = @Schema(implementation = DeliveryReservationResponse.class))),
            @ApiResponse(responseCode = "404", description = "Delivery reservation not found for the order",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "409", description = "Reservation is cancelled, confirmation is not possible",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class)))
    })
    ResponseEntity<DeliveryReservationResponse> confirm(
            @PathVariable Long orderId
    );

    /**
     * Компенсирующий шаг саги. Отменяет резерв доставки по `orderId`. Метод должен быть идемпотентным и безопасным для повторных вызовов.<br>
     * Возвращает результат: `CANCELLED`, `ALREADY_CANCELLED` или `NOT_FOUND`.<br>
     * Даже если резерв не найден, рекомендуется возвращать `200`, чтобы компенсация в саге не ломалась.
     *
     * @param orderId Long orderId
     * @return ResponseEntity DeliveryReservationResponse dto
     */
    @PostMapping("/{orderId}/cancel")
    @Operation(summary = "Cancel delivery reservation for order (internal)",
            description = "Saga compensation step. Cancels the delivery reservation by order ID. "
                    + "Idempotent and safe for repeated calls: returns the result "
                    + "CANCELLED, ALREADY_CANCELLED or NOT_FOUND. Returns 200 even when not found "
                    + "so that saga compensation never breaks.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Cancellation result returned",
                    content = @Content(schema = @Schema(implementation = CancelDeliveryResponse.class)))
    })
    ResponseEntity<CancelDeliveryResponse> cancel(
            @PathVariable Long orderId
    );
}
