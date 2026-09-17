package ru.otus.hw.consumer;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import ru.otus.hw.dto.UserAddressDto;
import ru.otus.hw.dto.UserCreatedEvent;
import ru.otus.hw.dto.UserSyncEvent;
import ru.otus.hw.models.AccountStatus;
import ru.otus.hw.models.User;
import ru.otus.hw.models.UserAddress;
import ru.otus.hw.producer.UserSyncEventPublisher;
import ru.otus.hw.repository.UserAddressRepository;
import ru.otus.hw.repository.UserRepository;
import ru.otus.hw.service.UserSyncEventAssembler;
import ru.otus.hw.tracing.W3CTraceContextAdapter;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Приём UserCreatedEvent (AUTH → USER): создание проекции users/user_profile/user_addresses
 * с id из события и публикация UserSyncEvent; идемпотентность повторной доставки.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserCreatedEventConsumerTest {

    private static final Long USER_ID = 42L;

    private static final String EVENT_ID = "evt-user-created-1";

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserAddressRepository userAddressRepository;

    @Mock
    private UserSyncEventPublisher userSyncEventPublisher;

    @Mock
    private UserSyncEventAssembler userSyncEventAssembler;

    @Mock
    private W3CTraceContextAdapter traceContextAdapter;

    @InjectMocks
    private UserCreatedEventConsumer consumer;

    private static @NonNull UserCreatedEvent event(List<UserAddressDto> addresses) {
        return UserCreatedEvent.builder()
                .eventId(EVENT_ID)
                .userId(USER_ID)
                .email("john@example.com")
                .userName("johndoe")
                .firstName("John")
                .lastName("Doe")
                .phone("+79991234567")
                .addresses(addresses)
                .timestamp(Instant.now())
                .build();
    }

    @Test
    @DisplayName("новый пользователь: users(id из события, PENDING) + профиль + адреса, "
            + "затем публикация UserSyncEvent")
    void shouldCreateProjectionAndPublishSyncEvent() {
        when(userRepository.existsById(USER_ID)).thenReturn(false);
        when(userSyncEventAssembler.assemble(USER_ID)).thenReturn(
                UserSyncEvent.builder().eventId("sync-1").userId(USER_ID).build());
        List<UserAddressDto> addresses = List.of(
                UserAddressDto.builder()
                        .fullAddress("Moscow, Tverskaya st. 7")
                        .city("Moscow")
                        .postalCode("125009")
                        .isDefault(true)
                        .build());

        consumer.handleUserCreated(event(addresses), Map.of());

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        User savedUser = userCaptor.getValue();
        assertThat(savedUser.getId()).isEqualTo(USER_ID);
        assertThat(savedUser.getEmail()).isEqualTo("john@example.com");
        assertThat(savedUser.getAccountStatus()).isEqualTo(AccountStatus.PENDING);
        assertThat(savedUser.getProfile()).isNotNull();
        assertThat(savedUser.getProfile().getUserName()).isEqualTo("johndoe");
        assertThat(savedUser.getProfile().getPhone()).isEqualTo("+79991234567");

        ArgumentCaptor<List<UserAddress>> addressesCaptor = ArgumentCaptor.forClass(List.class);
        verify(userAddressRepository).saveAll(addressesCaptor.capture());
        assertThat(addressesCaptor.getValue()).hasSize(1);
        assertThat(addressesCaptor.getValue().get(0).getUserId()).isEqualTo(USER_ID);
        assertThat(addressesCaptor.getValue().get(0).getIsDefault()).isTrue();

        verify(userSyncEventPublisher).send(any(UserSyncEvent.class));
    }

    @Test
    @DisplayName("повторная доставка (проекция уже есть): без дублей записей, "
            + "UserSyncEvent переопубликуется")
    void shouldNotDuplicateOnReplay() {
        when(userRepository.existsById(USER_ID)).thenReturn(true);
        when(userSyncEventAssembler.assemble(USER_ID)).thenReturn(
                UserSyncEvent.builder().eventId("sync-2").userId(USER_ID).build());

        consumer.handleUserCreated(event(List.of()), Map.of());

        verify(userRepository, never()).save(any(User.class));
        verify(userAddressRepository, never()).saveAll(anyList());
        verify(userSyncEventPublisher).send(any(UserSyncEvent.class));
    }

    @Test
    @DisplayName("событие без адресов: профиль создаётся, адреса не пишутся")
    void shouldCreateProjectionWithoutAddresses() {
        when(userRepository.existsById(USER_ID)).thenReturn(false);
        when(userSyncEventAssembler.assemble(USER_ID)).thenReturn(
                UserSyncEvent.builder().eventId("sync-3").userId(USER_ID).build());

        consumer.handleUserCreated(event(null), Map.of());

        verify(userRepository).save(any(User.class));
        verify(userAddressRepository, never()).saveAll(anyList());
        verify(userSyncEventPublisher).send(any(UserSyncEvent.class));
    }

    @Test
    @DisplayName("событие без userId: ack без исключения и без записей")
    void shouldSkipEventWithoutUserId() {
        UserCreatedEvent broken = UserCreatedEvent.builder()
                .eventId(EVENT_ID)
                .email("john@example.com")
                .timestamp(Instant.now())
                .build();

        assertThatCode(() -> consumer.handleUserCreated(broken, Map.of())).doesNotThrowAnyException();

        verify(userRepository, never()).save(any(User.class));
        verify(userSyncEventPublisher, never()).send(any(UserSyncEvent.class));
    }
}
