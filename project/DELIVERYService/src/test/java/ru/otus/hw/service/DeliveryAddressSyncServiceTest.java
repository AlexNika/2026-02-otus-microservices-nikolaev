package ru.otus.hw.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.otus.hw.dto.UserAddressDto;
import ru.otus.hw.dto.UserSyncEvent;
import ru.otus.hw.model.DeliveryUserAddress;
import ru.otus.hw.repository.DeliveryUserAddressRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Применение полного снимка адресов из UserSyncEvent к read-модели DELIVERYService:
 * upsert по (userId, sourceAddressId), удаление отсутствующих в снимке, пустой снимок -
 * удаление всех записей; повторная доставка и более старые события не меняют состояние.
 */
@ExtendWith(MockitoExtension.class)
class DeliveryAddressSyncServiceTest {

    private static final Long USER_ID = 7L;

    private static final Instant SNAPSHOT_TIME = Instant.parse("2026-08-12T10:00:00Z");

    @Mock
    private DeliveryUserAddressRepository addressRepository;

    @InjectMocks
    private DeliveryAddressSyncService deliveryAddressSyncService;

    private UserAddressDto address(Long addressId, String fullAddress, Boolean isDefault) {
        return UserAddressDto.builder()
                .addressId(addressId)
                .fullAddress(fullAddress)
                .city("Moscow")
                .postalCode("125009")
                .isDefault(isDefault)
                .deliveryPreferences(null)
                .build();
    }

    private UserSyncEvent event(Instant updatedAt, List<UserAddressDto> addresses) {
        return UserSyncEvent.builder()
                .eventId("sync-event-id")
                .userId(USER_ID)
                .email("john@example.com")
                .phone("+79991234567")
                .addresses(addresses)
                .updatedAt(updatedAt)
                .build();
    }

    private DeliveryUserAddress existing(Long localId, Long sourceAddressId, Instant updatedAt) {
        DeliveryUserAddress address = DeliveryUserAddress.builder()
                .userId(USER_ID)
                .sourceAddressId(sourceAddressId)
                .fullAddress("old line")
                .updatedAt(updatedAt)
                .build();
        address.setId(localId);
        return address;
    }

    @Test
    @DisplayName("первый снимок: создаются все записи по sourceAddressId + delete-not-in")
    void shouldCreateAllRecordsOnFirstSnapshot() {
        when(addressRepository.findMaxUpdatedAtByUserId(USER_ID)).thenReturn(Optional.empty());
        when(addressRepository.findByUserIdAndSourceAddressId(anyLong(), anyLong()))
                .thenReturn(Optional.empty());

        deliveryAddressSyncService.applyUserSync(event(SNAPSHOT_TIME,
                List.of(address(1L, "Moscow, Tverskaya st. 7", true),
                        address(2L, "SPb, Nevsky pr. 1", false))));

        ArgumentCaptor<DeliveryUserAddress> captor = ArgumentCaptor.forClass(DeliveryUserAddress.class);
        verify(addressRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(DeliveryUserAddress::getSourceAddressId)
                .containsExactly(1L, 2L);
        assertThat(captor.getAllValues())
                .extracting(DeliveryUserAddress::getFullAddress)
                .containsExactly("Moscow, Tverskaya st. 7", "SPb, Nevsky pr. 1");
        assertThat(captor.getAllValues())
                .allMatch(address -> SNAPSHOT_TIME.equals(address.getUpdatedAt()));

        verify(addressRepository).deleteAllByUserIdAndSourceAddressIdNotIn(USER_ID, List.of(1L, 2L));
    }

    @Test
    @DisplayName("повторный снимок: поля обновляются in-place, дубль не создаётся")
    void shouldUpdateExistingRecordsWithoutDuplicatesOnSecondSnapshot() {
        Instant newTime = Instant.parse("2026-08-12T11:00:00Z");
        DeliveryUserAddress persisted = existing(100L, 1L, SNAPSHOT_TIME);
        when(addressRepository.findMaxUpdatedAtByUserId(USER_ID)).thenReturn(Optional.of(SNAPSHOT_TIME));
        when(addressRepository.findByUserIdAndSourceAddressId(USER_ID, 1L)).thenReturn(Optional.of(persisted));

        deliveryAddressSyncService.applyUserSync(event(newTime,
                List.of(address(1L, "Moscow, Tverskaya st. 7 apt 5", true))));

        ArgumentCaptor<DeliveryUserAddress> captor = ArgumentCaptor.forClass(DeliveryUserAddress.class);
        verify(addressRepository).save(captor.capture());
        assertThat(captor.getValue()).isSameAs(persisted);
        assertThat(captor.getValue().getId()).isEqualTo(100L);
        assertThat(captor.getValue().getFullAddress()).isEqualTo("Moscow, Tverskaya st. 7 apt 5");
        assertThat(captor.getValue().getUpdatedAt()).isEqualTo(newTime);
    }

    @Test
    @DisplayName("адрес отсутствует в новом снимке: запись удаляется через delete-not-in")
    void shouldDeleteAddressAbsentInSnapshot() {
        Instant newTime = Instant.parse("2026-08-12T11:00:00Z");
        when(addressRepository.findMaxUpdatedAtByUserId(USER_ID)).thenReturn(Optional.of(SNAPSHOT_TIME));
        when(addressRepository.findByUserIdAndSourceAddressId(USER_ID, 1L))
                .thenReturn(Optional.of(existing(100L, 1L, SNAPSHOT_TIME)));

        deliveryAddressSyncService.applyUserSync(event(newTime, List.of(address(1L, "any", true))));

        verify(addressRepository).deleteAllByUserIdAndSourceAddressIdNotIn(USER_ID, List.of(1L));
    }

    @Test
    @DisplayName("пустой список адресов: все записи пользователя удаляются")
    void shouldDeleteAllRecordsWhenSnapshotIsEmpty() {
        when(addressRepository.findMaxUpdatedAtByUserId(USER_ID)).thenReturn(Optional.of(SNAPSHOT_TIME));

        deliveryAddressSyncService.applyUserSync(event(Instant.parse("2026-08-12T11:00:00Z"), List.of()));

        verify(addressRepository).deleteAllByUserId(USER_ID);
        verify(addressRepository, never()).save(any(DeliveryUserAddress.class));
    }

    @Test
    @DisplayName("null вместо списка адресов трактуется как пустой снимок: записи удаляются")
    void shouldDeleteAllRecordsWhenSnapshotIsNull() {
        when(addressRepository.findMaxUpdatedAtByUserId(USER_ID)).thenReturn(Optional.of(SNAPSHOT_TIME));

        deliveryAddressSyncService.applyUserSync(event(Instant.parse("2026-08-12T11:00:00Z"), null));

        verify(addressRepository).deleteAllByUserId(USER_ID);
        verify(addressRepository, never()).save(any(DeliveryUserAddress.class));
    }

    @Test
    @DisplayName("повторная доставка того же события (updatedAt равен локальному): состояние не меняется")
    void shouldNotChangeStateOnRedeliveryOfSameEvent() {
        when(addressRepository.findMaxUpdatedAtByUserId(USER_ID)).thenReturn(Optional.of(SNAPSHOT_TIME));

        deliveryAddressSyncService.applyUserSync(event(SNAPSHOT_TIME,
                List.of(address(1L, "Moscow, Tverskaya st. 7", true))));

        verify(addressRepository, never()).save(any(DeliveryUserAddress.class));
        verify(addressRepository, never()).deleteAllByUserId(anyLong());
        verify(addressRepository, never()).deleteAllByUserIdAndSourceAddressIdNotIn(anyLong(), anyList());
    }

    @Test
    @DisplayName("более старое событие: skip, состояние не меняется")
    void shouldSkipStaleEvent() {
        when(addressRepository.findMaxUpdatedAtByUserId(USER_ID)).thenReturn(Optional.of(SNAPSHOT_TIME));

        deliveryAddressSyncService.applyUserSync(event(Instant.parse("2026-08-12T09:00:00Z"),
                List.of(address(1L, "stale address", true))));

        verify(addressRepository, never()).save(any(DeliveryUserAddress.class));
        verify(addressRepository, never()).deleteAllByUserId(anyLong());
    }
}
