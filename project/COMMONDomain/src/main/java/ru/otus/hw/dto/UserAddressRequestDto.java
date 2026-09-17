package ru.otus.hw.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Элемент коллекции адресов доставки в запросах создания/обновления профиля.
 * {@code addressId == null} означает новый адрес; ненулевой {@code addressId} - обновление
 * существующего адреса пользователя при полной замене коллекции.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Delivery address of a user (element of the full snapshot)")
public record UserAddressRequestDto(
        @Schema(description = "Existing address id in USERService; null for a new address",
                example = "1", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Long addressId,

        @Schema(description = "Full address line", example = "Moscow, Tverskaya st. 7, apt 12",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "Full address is mandatory and cannot be null or empty")
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
