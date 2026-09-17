package ru.otus.hw.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Адрес доставки пользователя в ответах API. {@code addressId} - id адреса
 * в {@code user_addresses} USERService (source of truth).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Delivery address of a user (response)")
public record UserAddressResponseDto(
        @Schema(description = "Address id in USERService", example = "1",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Long addressId,

        @Schema(description = "Full address line", example = "Moscow, Tverskaya st. 7, apt 12",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String fullAddress,

        @Schema(description = "City", example = "Moscow",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String city,

        @Schema(description = "Postal code", example = "125009",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String postalCode,

        @Schema(description = "Whether this is the default delivery address", example = "true",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Boolean isDefault,

        @Schema(description = "Free-form delivery preferences", example = "Call before delivery",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String deliveryPreferences) {
}
