package ru.otus.hw.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.otus.hw.dto.UserAddressRequestDto;
import ru.otus.hw.dto.UserProfileDto;
import ru.otus.hw.dto.UserProfileFullUpdateDto;
import ru.otus.hw.dto.UserProfileUpdateDto;
import ru.otus.hw.dto.mapper.UserMapper;
import ru.otus.hw.exception.DuplicateResourceException;
import ru.otus.hw.exception.UserNotFoundException;
import ru.otus.hw.models.UserAddress;
import ru.otus.hw.models.UserProfile;
import ru.otus.hw.producer.UserSyncEventPublisher;
import ru.otus.hw.repository.UserAddressRepository;
import ru.otus.hw.repository.UserProfileRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Управление профилем пользователя: контакты (phone) и адреса доставки (1:N).
 *
 * <p>После каждого изменения публикуется {@code UserSyncEvent} (полный снимок) через общий
 * transactional outbox - NOTIFICATION и DELIVERY обновляют свои read-модели асинхронно,
 * синхронные REST-вызовы к USERService для этих данных больше не требуются.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserProfileServiceImpl implements UserProfileService {

    private final UserProfileRepository profileRepository;

    private final UserAddressRepository userAddressRepository;

    private final UserMapper mapper;

    private final UserSyncEventPublisher userSyncEventPublisher;

    private final UserSyncEventAssembler userSyncEventAssembler;

    @Override
    @Transactional(readOnly = true)
    public UserProfileDto getProfile(Long userId) {
        log.info("Fetching profile for user ID: {}", userId);
        UserProfile profile = profileRepository.findByUserId(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        List<UserAddress> addresses = userAddressRepository.findAllByUserId(userId);
        return mapper.toProfileDto(profile, addresses);
    }

    /**
     * Обновление профиля с синхронизацией адресов доставки.
     *
     * <p>Семантика коллекции адресов: {@code null} - не трогать; пустой список - удалить все;
     * иначе полная замена снимка: элементы с известным {@code addressId} обновляют существующие
     * записи, элементы без id создаются, отсутствующие в запросе записи удаляются.
     *
     * <p>Все изменения и запись UserSyncEvent в outbox выполняются в одной транзакции.
     */
    @Override
    @Transactional
    public UserProfileDto updateProfile(Long userId, UserProfileUpdateDto updateDto) {
        log.info("Updating profile for user ID: {}", userId);
        UserProfile profile = profileRepository.findByUserId(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        mapper.updateProfileFromDto(updateDto, profile);
        UserProfile updatedProfile;
        try {
            updatedProfile = profileRepository.saveAndFlush(profile);
        } catch (DataIntegrityViolationException e) {
            log.warn("Duplicate phone while updating profile of user ID: {}: {}", userId,
                    updateDto.phone());
            throw new DuplicateResourceException("Cannot update profile: phone '"
                    + updateDto.phone() + "' is already registered");
        }

        syncAddresses(userId, updateDto.addresses());

        userSyncEventPublisher.send(userSyncEventAssembler.assemble(userId));

        List<UserAddress> addresses = userAddressRepository.findAllByUserId(userId);
        log.info("Profile updated successfully for user ID: {} ({} address(es))", userId, addresses.size());

        return mapper.toProfileDto(updatedProfile, addresses);
    }

    /**
     * Полная замена профиля (семантика PUT): все поля устанавливаются как есть,
     * {@code null} в опциональных полях очищает текущее значение; {@code addresses=null}
     * трактуется как пустой список - снимок адресов удаляется целиком.
     *
     * <p>Все изменения и запись UserSyncEvent в outbox выполняются в одной транзакции.
     */
    @Override
    @Transactional
    public UserProfileDto replaceProfile(Long userId, UserProfileFullUpdateDto updateDto) {
        log.info("Replacing profile for user ID: {}", userId);
        UserProfile profile = profileRepository.findByUserId(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        profile.setUserName(updateDto.userName());
        profile.setFirstName(updateDto.firstName());
        profile.setLastName(updateDto.lastName());
        profile.setBirthdate(updateDto.birthdate());
        profile.setPhone(updateDto.phone());

        UserProfile updatedProfile;
        try {
            updatedProfile = profileRepository.saveAndFlush(profile);
        } catch (DataIntegrityViolationException e) {
            log.warn("Duplicate phone while replacing profile of user ID: {}: {}", userId,
                    updateDto.phone());
            throw new DuplicateResourceException("Cannot replace profile: phone '"
                    + updateDto.phone() + "' is already registered");
        }

        syncAddresses(userId, updateDto.addresses() != null ? updateDto.addresses() : List.of());

        userSyncEventPublisher.send(userSyncEventAssembler.assemble(userId));

        List<UserAddress> addresses = userAddressRepository.findAllByUserId(userId);
        log.info("Profile replaced successfully for user ID: {} ({} address(es))", userId, addresses.size());

        return mapper.toProfileDto(updatedProfile, addresses);
    }

    /**
     * Полная замена снимка адресов: update по найденному id, insert новых без id,
     * delete отсутствующих в запросе.
     */
    private void syncAddresses(@NonNull Long userId, List<UserAddressRequestDto> requestAddresses) {
        if (requestAddresses == null) {
            log.debug("Addresses not provided for user ID: {}, keeping current set", userId);
            return;
        }
        Map<Long, UserAddress> existingById = new LinkedHashMap<>();
        userAddressRepository.findAllByUserId(userId)
                .forEach(existing -> existingById.put(existing.getId(), existing));
        if (requestAddresses.isEmpty()) {
            log.info("Removing all {} address(es) for user ID: {}", existingById.size(), userId);
            userAddressRepository.deleteAllByUserId(userId);
            return;
        }
        List<UserAddress> toSave = requestAddresses.stream()
                .map(dto -> mergeInto(userId, dto, existingById))
                .toList();
        userAddressRepository.saveAll(toSave);
        if (!existingById.isEmpty()) {
            log.info("Removing {} address(es) absent in the snapshot for user ID: {}",
                    existingById.size(), userId);
            userAddressRepository.deleteAll(existingById.values());
        }
    }

    /**
     * Обновляет существующий адрес по id либо строит новый; запись, найденная
     * в {@code existingById}, удаляется из карты (останутся только отсутствующие в снимке).
     */
    private @NonNull UserAddress mergeInto(@NonNull Long userId, @NonNull UserAddressRequestDto dto,
                                           @NonNull Map<Long, UserAddress> existingById) {
        UserAddress target = dto.addressId() != null
                ? existingById.remove(dto.addressId())
                : null;
        if (target == null) {
            target = UserAddress.builder().userId(userId).build();
        }
        target.setFullAddress(dto.fullAddress());
        target.setCity(dto.city());
        target.setPostalCode(dto.postalCode());
        target.setIsDefault(dto.isDefault() != null ? dto.isDefault() : Boolean.FALSE);
        target.setDeliveryPreferences(dto.deliveryPreferences());
        return target;
    }
}
