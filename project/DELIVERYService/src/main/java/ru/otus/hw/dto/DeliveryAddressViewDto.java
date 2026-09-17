package ru.otus.hw.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Внутренний контракт read-модели адреса доставки для эндпоинта
 * {@code GET /internal/delivery/user-addresses/{userId}}. В COMMONDomain не выносится:
 * это контракт одного сервиса (верификация репликации и будущий OrderService/сага).
 */
@Schema(description = "Read-model view of a user delivery address")
public record DeliveryAddressViewDto(

        @Schema(description = "Identifier of the address in the source service", example = "5")
        Long sourceAddressId,

        @Schema(description = "Full address string", example = "Moscow, Tverskaya st., 1, apt. 10")
        String fullAddress,

        @Schema(description = "City", example = "Moscow")
        String city,

        @Schema(description = "Postal code", example = "125009")
        String postalCode,

        @Schema(description = "Whether the address is the user's default one", example = "true")
        Boolean isDefault,

        @Schema(description = "Free-form delivery preferences", example = "Call on arrival")
        String preferences) {
}
