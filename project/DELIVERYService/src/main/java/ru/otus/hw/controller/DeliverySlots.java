package ru.otus.hw.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ru.otus.hw.dto.AvailableSlotsResponse;
import ru.otus.hw.dto.ErrorDto;

import java.time.LocalDate;

@RequestMapping("/api/v1/delivery")
@Tag(name = "Delivery Slots API", description = "REST API for browsing available delivery slots")
@SecurityRequirement(name = "basicAuth")
public interface DeliverySlots {

    /**
     * Получение списка временных слотов на дату с признаком доступности.<br>
     * Не раскрывает внутренние данные вроде `courierCount` или `reservedCount`. Пользователь видит только слот и признак `available`.<br>
     * Если на дату нет настроенных слотов, допустимо вернуть `200` с пустым списком.<br>
     * Коды ошибок:<br>
     * `400` - некорректный формат даты или отсутствует параметр `date`.
     *
     * @param date LocalDate date - DateTimeFormat.ISO.DATE
     * @return ResponseEntity AvailableSlotsResponse dto
     */
    @GetMapping("/slots")
    @Operation(summary = "Get available delivery slots",
            description = "Retrieves the list of time slots for a date with an availability flag. "
                    + "Does not expose internal data such as courierCount or reservedCount - "
                    + "only the slot and the available flag are visible. "
                    + "If no slots are configured for the date, returns 200 with an empty list.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "List of slots retrieved",
                    content = @Content(schema = @Schema(implementation = AvailableSlotsResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid date format or missing date parameter",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class)))
    })
    ResponseEntity<AvailableSlotsResponse> getAvailableSlots(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    );
}
