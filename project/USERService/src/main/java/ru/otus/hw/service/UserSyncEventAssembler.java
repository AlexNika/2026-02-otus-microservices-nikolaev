package ru.otus.hw.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.otus.hw.dto.UserAddressDto;
import ru.otus.hw.dto.UserSyncEvent;
import ru.otus.hw.exception.UserNotFoundException;
import ru.otus.hw.models.User;
import ru.otus.hw.models.UserProfile;
import ru.otus.hw.repository.UserAddressRepository;
import ru.otus.hw.repository.UserRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Единая точка сборки события {@link UserSyncEvent}: читает актуальный снимок пользователя
 * (email, phone, адреса доставки) и строит полный снимок. Вызывается всеми издателями
 * (регистрация, update/patch пользователя, обновление профиля) после фиксации бизнес-изменений
 * в той же транзакции, поэтому событие всегда согласовано с сохранённым состоянием.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserSyncEventAssembler {

    private final UserRepository userRepository;

    private final UserAddressRepository userAddressRepository;

    /**
     * Собирает полный снимок контактных данных и адресов пользователя.
     */
    @Transactional(readOnly = true)
    public @NonNull UserSyncEvent assemble(@NonNull Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        UserProfile profile = user.getProfile();
        String phone = profile != null ? profile.getPhone() : null;

        List<UserAddressDto> addressDtos = toAddressDtos(userId);

        UserSyncEvent event = UserSyncEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .userId(userId)
                .email(user.getEmail())
                .phone(phone)
                .addresses(addressDtos)
                .updatedAt(Instant.now())
                .build();
        log.debug("Assembled UserSyncEvent: userId={}, eventId={}, addresses={}",
                userId, event.eventId(), addressDtos.size());
        return event;
    }

    private @NonNull List<UserAddressDto> toAddressDtos(@NonNull Long userId) {
        return userAddressRepository.findAllByUserId(userId).stream()
                .map(address -> UserAddressDto.builder()
                        .addressId(address.getId())
                        .fullAddress(address.getFullAddress())
                        .city(address.getCity())
                        .postalCode(address.getPostalCode())
                        .isDefault(address.getIsDefault())
                        .deliveryPreferences(address.getDeliveryPreferences())
                        .build())
                .toList();
    }
}
