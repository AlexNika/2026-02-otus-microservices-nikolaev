package ru.otus.hw.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;

import java.time.Instant;
import java.util.List;

/**
 * Событие канала USER → NOTIFICATION/DELIVERY: полный снимок контактных данных пользователя
 * (email, phone) и его адресов доставки (0..N). Публикуется USERService при регистрации,
 * смене email ({@code PUT/PATCH /api/v1/user/{id}}) и обновлении профиля
 * ({@code PUT/PATCH /api/v1/profile}).
 *
 * <p>Потребители поддерживают локальные read-модели: NOTIFICATION - контакты (email, phone),
 * DELIVERY - адреса доставки. Синхронные REST-вызовы к USERService для этих данных больше
 * не требуются.
 *
 * <p>{@code eventId} - UUID, ключ идемпотентности сообщения: NOTIFICATION дедуплицирует по нему
 * через {@code processed_messages}; DELIVERY идемпотентен естественным образом (upsert по
 * natural key {@code (userId, sourceAddressId)} + удаление отсутствующих в снимке).
 *
 * <p>Защита от доставки out-of-order: потребители применяют событие только если {@code updatedAt}
 * новее локальной версии данных (timestamp guard).
 *
 * <p>Осознанное ограничение: удаление пользователя ({@code DELETE /api/v1/user/{id}}) событие
 * НЕ публикует - read-модели могут хранить устаревшую запись (событие UserDeletedEvent вне рамок).
 */
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public record UserSyncEvent(
        String eventId,
        Long userId,
        String email,
        String phone,
        List<UserAddressDto> addresses,
        Instant updatedAt) {
}
