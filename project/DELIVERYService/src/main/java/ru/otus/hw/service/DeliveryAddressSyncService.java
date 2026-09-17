package ru.otus.hw.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.otus.hw.dto.UserAddressDto;
import ru.otus.hw.dto.UserSyncEvent;
import ru.otus.hw.model.DeliveryUserAddress;
import ru.otus.hw.repository.DeliveryUserAddressRepository;

import java.time.Instant;
import java.util.List;

/**
 * Применение {@link UserSyncEvent} к локальной read-модели адресов доставки
 * (таблица {@code delivery_user_addresses}, 1:N по user_id).
 *
 * <p>Идемпотентность естественная: полный снимок применяется как upsert по natural key
 * {@code (user_id, source_address_id)} + удаление записей, отсутствующих в снимке.
 * Повторная доставка того же события приводит к тому же состоянию.
 *
 * <p>Защита от out-of-order доставки: событие пропускается, если локальные данные
 * ({@code max(updated_at)} по пользователю) новее {@code event.updatedAt()}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryAddressSyncService {

    private final DeliveryUserAddressRepository addressRepository;

    @Transactional
    public void applyUserSync(@NonNull UserSyncEvent event) {
        Long userId = event.userId();

        Instant localMaxUpdatedAt = addressRepository.findMaxUpdatedAtByUserId(userId).orElse(null);
        if (localMaxUpdatedAt != null
                && event.updatedAt() != null
                && !localMaxUpdatedAt.isBefore(event.updatedAt())) {
            log.info("Skipping stale user-sync event: userId={}, localMaxUpdatedAt={}, eventUpdatedAt={}",
                    userId, localMaxUpdatedAt, event.updatedAt());
            return;
        }

        List<UserAddressDto> incoming = event.addresses();
        if (incoming == null || incoming.isEmpty()) {
            log.info("User-sync snapshot has no addresses, removing all for userId={}, eventId={}",
                    userId, event.eventId());
            addressRepository.deleteAllByUserId(userId);
            return;
        }

        applySnapshot(event);

        log.info("Address read-model updated for userId={}, eventId={}, addresses={}",
                userId, event.eventId(), incoming.size());
    }

    private void applySnapshot(@NonNull UserSyncEvent event) {
        Long userId = event.userId();
        for (UserAddressDto dto : event.addresses()) {
            applyOne(event, dto);
        }
        List<Long> incomingIds = event.addresses().stream()
                .map(UserAddressDto::addressId)
                .filter(java.util.Objects::nonNull)
                .toList();
        addressRepository.deleteAllByUserIdAndSourceAddressIdNotIn(userId, incomingIds);
    }

    private void applyOne(@NonNull UserSyncEvent event, @NonNull UserAddressDto dto) {
        if (dto.addressId() == null) {
            log.warn("User-sync address without addressId skipped: userId={}, eventId={}",
                    event.userId(), event.eventId());
            return;
        }
        DeliveryUserAddress address = addressRepository
                .findByUserIdAndSourceAddressId(event.userId(), dto.addressId())
                .orElseGet(() -> DeliveryUserAddress.builder()
                        .userId(event.userId())
                        .sourceAddressId(dto.addressId())
                        .build());
        address.setFullAddress(dto.fullAddress());
        address.setCity(dto.city());
        address.setPostalCode(dto.postalCode());
        address.setIsDefault(dto.isDefault() != null ? dto.isDefault() : Boolean.FALSE);
        address.setPreferences(dto.deliveryPreferences());
        address.setUpdatedAt(event.updatedAt());
        addressRepository.save(address);
    }
}
