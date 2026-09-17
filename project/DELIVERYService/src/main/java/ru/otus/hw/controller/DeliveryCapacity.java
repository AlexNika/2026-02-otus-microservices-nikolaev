package ru.otus.hw.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import ru.otus.hw.dto.CapacityResponse;
import ru.otus.hw.dto.ErrorDto;
import ru.otus.hw.dto.SetCourierCapacityRequest;

import java.time.LocalDate;

@RequestMapping("/api/v1/delivery/courier-capacity")
@Tag(name = "Delivery Courier Capacity API", description = "REST API for courier capacity management")
@SecurityRequirement(name = "basicAuth")
public interface DeliveryCapacity {

    /**
     * Установка количества курьеров на конкретную дату. Если список слотов не передан, используются default-слоты из конфигурации.
     * Если слоты переданы, сервис создаёт или обновляет их.<br>
     * Коды ошибок:<br>
     * `400` - ошибка валидации, например пересекающиеся слоты или некорректное время;<br>
     * `409` - новое количество курьеров меньше, чем уже активно зарезервировано в одном из слотов.<br>
     *
     * @param date LocalDate date - DateTimeFormat.ISO.DAT
     * @return ResponseEntity CapacityResponse dto
     * @body SetCourierCapacityRequest dto
     */
    @PutMapping("/{date}")
    @Operation(summary = "Set courier capacity for a date",
            description = "Sets the number of couriers for a specific date. If no slot list is provided, "
                    + "default slots from the configuration are used. If slots are provided, "
                    + "the service creates or updates them.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Courier capacity set successfully",
                    content = @Content(schema = @Schema(implementation = CapacityResponse.class))),
            @ApiResponse(responseCode = "400", description = "Validation failed, e.g. overlapping slots or invalid time",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "403", description = "Access denied (ADMIN role required)",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "409",
                    description = "New courier capacity is less than already actively reserved in one of the slots",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class)))
    })
    ResponseEntity<CapacityResponse> setCapacity(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @Valid @RequestBody SetCourierCapacityRequest request
    );

    /**
     * Получение настроенной ёмкости курьеров на дату. Возвращает общее количество курьеров на день и список слотов с `capacity` и `reservedCount`.<br>
     * Коды ошибок:<br>
     * `400` - ошибка валидации, например пересекающиеся слоты или некорректное время;<br>
     *
     * @param date LocalDate date - DateTimeFormat.ISO.DATE
     * @return ResponseEntity CapacityResponse dto
     */
    @GetMapping("/{date}")
    @Operation(summary = "Get courier capacity for a date",
            description = "Retrieves the configured courier capacity for a date: the total number of couriers "
                    + "per day and the list of slots with capacity and reservedCount")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Courier capacity found",
                    content = @Content(schema = @Schema(implementation = CapacityResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid date format",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class)))
    })
    ResponseEntity<CapacityResponse> getCapacity(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    );
}
