package ru.otus.hw.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import ru.otus.hw.dto.UserAddressRequestDto;
import ru.otus.hw.dto.UserProfileDto;
import ru.otus.hw.dto.UserProfileFullUpdateDto;
import ru.otus.hw.dto.UserProfileUpdateDto;
import ru.otus.hw.dto.UserSyncEvent;
import ru.otus.hw.dto.mapper.UserMapper;
import ru.otus.hw.exception.DuplicateResourceException;
import ru.otus.hw.exception.UserNotFoundException;
import ru.otus.hw.models.UserAddress;
import ru.otus.hw.models.UserProfile;
import ru.otus.hw.producer.UserSyncEventPublisher;
import ru.otus.hw.repository.UserAddressRepository;
import ru.otus.hw.repository.UserProfileRepository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Обновление профиля: phone upsert, семантика коллекции адресов
 * (null - не трогать, [] - удалить все, иначе полная замена) и публикация
 * UserSyncEvent с актуальным снимком; дубликат phone → DuplicateResourceException.
 * Плюс полная замена (PUT): прямая установка полей с очисткой через null,
 * addresses=null - пустой снимок.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserProfileServiceImplTest {

    private static final Long USER_ID = 7L;

    @Mock
    private UserProfileRepository profileRepository;

    @Mock
    private UserAddressRepository userAddressRepository;

    @Mock
    private UserMapper mapper;

    @Mock
    private UserSyncEventPublisher userSyncEventPublisher;

    @Mock
    private UserSyncEventAssembler userSyncEventAssembler;

    private UserProfileServiceImpl profileService;

    private UserProfile profile;

    @BeforeEach
    void setUp() {
        profileService = new UserProfileServiceImpl(profileRepository, userAddressRepository, mapper,
                userSyncEventPublisher, userSyncEventAssembler);
        profile = UserProfile.builder()
                .userName("johndoe")
                .build();
        profile.setId(1L);
        when(profileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile));
        when(profileRepository.saveAndFlush(any(UserProfile.class))).thenAnswer(inv -> inv.getArgument(0));
        when(mapper.toProfileDto(any(UserProfile.class), anyList()))
                .thenReturn(new UserProfileDto("johndoe", "John", "Doe", null, "+79991234567", List.of()));
        when(userAddressRepository.findAllByUserId(USER_ID)).thenReturn(List.of());
        when(userSyncEventAssembler.assemble(USER_ID)).thenReturn(UserSyncEvent.builder()
                .eventId("sync-event-id")
                .userId(USER_ID)
                .email("john@example.com")
                .phone("+79991234567")
                .addresses(List.of())
                .updatedAt(Instant.now())
                .build());
    }

    private UserProfileUpdateDto updateDto(String phone, List<UserAddressRequestDto> addresses) {
        return new UserProfileUpdateDto(null, null, null, null, phone, addresses);
    }

    private UserProfileFullUpdateDto fullUpdateDto(String userName, String firstName, String lastName,
            LocalDateTime birthdate, String phone, List<UserAddressRequestDto> addresses) {
        return new UserProfileFullUpdateDto(userName, firstName, lastName, birthdate, phone, addresses);
    }

    private UserAddress existingAddress(Long id, String fullAddress) {
        UserAddress address = UserAddress.builder()
                .userId(USER_ID)
                .fullAddress(fullAddress)
                .build();
        address.setId(id);
        return address;
    }

    @Test
    @DisplayName("phone передаётся в профиль маппером и фиксируется saveAndFlush")
    void shouldUpsertPhoneThroughMapper() {
        profileService.updateProfile(USER_ID, updateDto("+79991234567", null));

        verify(mapper).updateProfileFromDto(any(UserProfileUpdateDto.class), any(UserProfile.class));
        verify(profileRepository).saveAndFlush(profile);
    }

    @Test
    @DisplayName("addresses=null: коллекция адресов не изменяется")
    void shouldNotTouchAddressesWhenNull() {
        profileService.updateProfile(USER_ID, updateDto("+79991234567", null));

        verify(userAddressRepository, never()).saveAll(anyCollection());
        verify(userAddressRepository, never()).deleteAllByUserId(USER_ID);
        verify(userAddressRepository, never()).deleteAll(anyCollection());
    }

    @Test
    @DisplayName("addresses=[]: все адреса пользователя удаляются")
    void shouldDeleteAllAddressesWhenEmptyList() {
        profileService.updateProfile(USER_ID, updateDto(null, List.of()));

        verify(userAddressRepository).deleteAllByUserId(USER_ID);
        verify(userAddressRepository, never()).saveAll(anyCollection());
    }

    @Test
    @DisplayName("полная замена снимка: существующий адрес обновлён по id, новый создан, отсутствующий удалён")
    void shouldReplaceAddressSnapshot() {
        UserAddress kept = existingAddress(1L, "Old address line");
        UserAddress removed = existingAddress(2L, "Address to remove");
        when(userAddressRepository.findAllByUserId(USER_ID)).thenReturn(List.of(kept, removed));

        List<UserAddressRequestDto> request = List.of(
                new UserAddressRequestDto(1L, "Updated address line", "Moscow", "125009", true, null),
                new UserAddressRequestDto(null, "Brand new address", "SPb", "191186", false, "leave at door"));

        profileService.updateProfile(USER_ID, updateDto(null, request));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UserAddress>> saveCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(userAddressRepository).saveAll(saveCaptor.capture());
        List<UserAddress> saved = List.copyOf(saveCaptor.getValue());
        assertThat(saved).hasSize(2);

        UserAddress updated = saved.get(0);
        assertThat(updated.getId()).isEqualTo(1L);
        assertThat(updated.getFullAddress()).isEqualTo("Updated address line");
        assertThat(updated.getCity()).isEqualTo("Moscow");
        assertThat(updated.getIsDefault()).isTrue();

        UserAddress created = saved.get(1);
        assertThat(created.getId()).isNull();
        assertThat(created.getUserId()).isEqualTo(USER_ID);
        assertThat(created.getFullAddress()).isEqualTo("Brand new address");
        assertThat(created.getDeliveryPreferences()).isEqualTo("leave at door");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UserAddress>> deleteCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(userAddressRepository).deleteAll(deleteCaptor.capture());
        assertThat(deleteCaptor.getValue()).containsExactly(removed);
    }

    @Test
    @DisplayName("после изменений публикуется UserSyncEvent с актуальным снимком")
    void shouldPublishUserSyncEventAfterUpdate() {
        profileService.updateProfile(USER_ID, updateDto("+79991234567", List.of()));

        ArgumentCaptor<UserSyncEvent> captor = ArgumentCaptor.forClass(UserSyncEvent.class);
        verify(userSyncEventPublisher).send(captor.capture());
        assertThat(captor.getValue().userId()).isEqualTo(USER_ID);
        verify(userSyncEventAssembler).assemble(USER_ID);
    }

    @Test
    @DisplayName("дубликат phone: DataIntegrityViolationException перехватывается и ре-бросается "
            + "как DuplicateResourceException")
    void shouldThrowDuplicateResourceExceptionOnPhoneConflict() {
        when(profileRepository.saveAndFlush(any(UserProfile.class)))
                .thenThrow(new DataIntegrityViolationException("uk_user_profile_phone"));

        assertThatThrownBy(() -> profileService.updateProfile(USER_ID, updateDto("+79991234567", null)))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("+79991234567");

        verify(userSyncEventPublisher, never()).send(any(UserSyncEvent.class));
    }

    @Test
    @DisplayName("replaceProfile: полное замещение - поля устанавливаются напрямую, включая очистку через null")
    void shouldReplaceProfileSettingAllFieldsDirectly() {
        profile.setFirstName("John");
        profile.setLastName("Doe");
        profile.setBirthdate(birthdate());
        profile.setPhone("+79991234567");

        profileService.replaceProfile(USER_ID,
                fullUpdateDto("newname", null, null, null, null, null));

        assertThat(profile.getUserName()).isEqualTo("newname");
        assertThat(profile.getFirstName()).isNull();
        assertThat(profile.getLastName()).isNull();
        assertThat(profile.getBirthdate()).isNull();
        assertThat(profile.getPhone()).isNull();
        verify(mapper, never()).updateProfileFromDto(any(UserProfileUpdateDto.class), any(UserProfile.class));
        verify(profileRepository).saveAndFlush(profile);
    }

    @Test
    @DisplayName("replaceProfile: все переданные значения записываются в профиль")
    void shouldReplaceProfileWithProvidedValues() {
        LocalDateTime birthdate = birthdate();

        profileService.replaceProfile(USER_ID,
                fullUpdateDto("newname", "Alice", "Smith", birthdate, "+79990000000", List.of()));

        assertThat(profile.getUserName()).isEqualTo("newname");
        assertThat(profile.getFirstName()).isEqualTo("Alice");
        assertThat(profile.getLastName()).isEqualTo("Smith");
        assertThat(profile.getBirthdate()).isEqualTo(birthdate);
        assertThat(profile.getPhone()).isEqualTo("+79990000000");
    }

    @Test
    @DisplayName("replaceProfile: addresses=null трактуется как пустой снимок - все адреса удаляются")
    void shouldDeleteAllAddressesWhenFullUpdateAddressesNull() {
        profileService.replaceProfile(USER_ID,
                fullUpdateDto("newname", null, null, null, null, null));

        verify(userAddressRepository).deleteAllByUserId(USER_ID);
        verify(userAddressRepository, never()).saveAll(anyCollection());
    }

    @Test
    @DisplayName("replaceProfile: после изменений публикуется UserSyncEvent с актуальным снимком")
    void shouldPublishUserSyncEventAfterReplace() {
        profileService.replaceProfile(USER_ID,
                fullUpdateDto("newname", null, null, null, null, List.of()));

        verify(userSyncEventPublisher).send(any(UserSyncEvent.class));
        verify(userSyncEventAssembler).assemble(USER_ID);
    }

    @Test
    @DisplayName("replaceProfile: дубликат phone → DuplicateResourceException, событие не публикуется")
    void shouldThrowDuplicateResourceExceptionOnReplacePhoneConflict() {
        when(profileRepository.saveAndFlush(any(UserProfile.class)))
                .thenThrow(new DataIntegrityViolationException("uk_user_profile_phone"));

        assertThatThrownBy(() -> profileService.replaceProfile(USER_ID,
                fullUpdateDto("newname", null, null, null, "+79991234567", null)))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("+79991234567");

        verify(userSyncEventPublisher, never()).send(any(UserSyncEvent.class));
    }

    @Test
    @DisplayName("replaceProfile: профиль не найден → UserNotFoundException")
    void shouldThrowUserNotFoundOnReplaceWhenProfileMissing() {
        when(profileRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> profileService.replaceProfile(USER_ID,
                fullUpdateDto("newname", null, null, null, null, null)))
                .isInstanceOf(UserNotFoundException.class);

        verify(profileRepository, never()).saveAndFlush(any(UserProfile.class));
    }

    private LocalDateTime birthdate() {
        return LocalDateTime.of(1990, 1, 15, 0, 0);
    }
}
