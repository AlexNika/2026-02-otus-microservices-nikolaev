package ru.otus.hw.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;

/**
 * Адрес доставки пользователя в событии {@link UserSyncEvent} - элемент полного снимка (0..N).
 *
 * <p>{@code addressId} - id адреса в таблице {@code user_addresses} USERService (source of truth);
 * потребители (NOTIFICATION/DELIVERY) используют его как natural key своей read-модели.
 */
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public record UserAddressDto(
        Long addressId,
        String fullAddress,
        String city,
        String postalCode,
        Boolean isDefault,
        String deliveryPreferences) {
}
