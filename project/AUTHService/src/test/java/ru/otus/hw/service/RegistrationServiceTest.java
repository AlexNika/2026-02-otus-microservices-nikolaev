package ru.otus.hw.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;
import ru.otus.hw.dto.RegisterResponseDto;
import ru.otus.hw.dto.UserAddressRequestDto;
import ru.otus.hw.dto.UserCreateDto;
import ru.otus.hw.dto.UserCreatedEvent;
import ru.otus.hw.exception.DuplicateResourceException;
import ru.otus.hw.models.AuthUser;
import ru.otus.hw.models.Role;
import ru.otus.hw.producer.UserCreatedEventPublisher;
import ru.otus.hw.repository.AuthUserRepository;
import ru.otus.hw.repository.RoleRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Регистрация в AuthService: credentials + расширенный UserCreatedEvent через outbox,
 * идемпотентность eventId, дубликат email -> 409.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RegistrationServiceTest {

    private static final Long USER_ID = 42L;

    @Mock
    private AuthUserRepository authUserRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private UserCreatedEventPublisher userCreatedEventPublisher;

    @Spy
    private MeterRegistry meterRegistry = new SimpleMeterRegistry();

    @InjectMocks
    private RegistrationServiceImpl registrationService;

    private @NonNull UserCreateDto createDto() {
        return new UserCreateDto("johndoe", "John", "Doe", "John@Example.com", "Password1!",
                "+79991234567", List.of(
                        new UserAddressRequestDto(null, "Moscow, Tverskaya st. 7", "Moscow",
                                "125009", true, "Call before delivery")));
    }

    private void stubNewUser() {
        when(authUserRepository.existsByEmail("john@example.com")).thenReturn(false);
        Role userRole = new Role();
        userRole.setName("USER");
        when(roleRepository.findByName("USER")).thenReturn(Optional.of(userRole));
        when(passwordEncoder.encode("Password1!")).thenReturn("$2a$12$hash");
        when(authUserRepository.saveAndFlush(any(AuthUser.class))).thenAnswer(inv -> {
            AuthUser user = inv.getArgument(0);
            user.setId(USER_ID);
            return user;
        });
    }

    @Test
    @DisplayName("регистрация: credentials (email нормализован, пароль захеширован, роль USER) "
            + "и расширенный UserCreatedEvent c детерминированным eventId")
    void shouldSaveCredentialsAndPublishExtendedEvent() {
        stubNewUser();

        RegisterResponseDto response = registrationService.register(createDto());

        assertThat(response.id()).isEqualTo(USER_ID);
        assertThat(response.email()).isEqualTo("john@example.com");
        assertThat(meterRegistry.counter("auth.registrations").count()).isEqualTo(1.0);

        ArgumentCaptor<UserCreatedEvent> eventCaptor = ArgumentCaptor.forClass(UserCreatedEvent.class);
        verify(userCreatedEventPublisher).send(eventCaptor.capture());
        UserCreatedEvent event = eventCaptor.getValue();
        assertThat(event.userId()).isEqualTo(USER_ID);
        assertThat(event.email()).isEqualTo("john@example.com");
        assertThat(event.userName()).isEqualTo("johndoe");
        assertThat(event.firstName()).isEqualTo("John");
        assertThat(event.lastName()).isEqualTo("Doe");
        assertThat(event.phone()).isEqualTo("+79991234567");
        assertThat(event.addresses()).hasSize(1);
        assertThat(event.addresses().getFirst().fullAddress()).isEqualTo("Moscow, Tverskaya st. 7");
        String expectedEventId = UUID.nameUUIDFromBytes(
                ("user-created:" + USER_ID).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        assertThat(event.eventId()).isEqualTo(expectedEventId);
    }

    @Test
    @DisplayName("повторная регистрация с тем же email -> 409 DuplicateResourceException")
    void shouldRejectDuplicateEmail() {
        when(authUserRepository.existsByEmail("john@example.com")).thenReturn(true);

        assertThatThrownBy(() -> registrationService.register(createDto()))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("john@example.com");

        verify(authUserRepository, never()).saveAndFlush(any());
        verify(userCreatedEventPublisher, never()).send(any());
    }

    @Test
    @DisplayName("нет роли USER в БД -> IllegalStateException (fail-fast конфигурации)")
    void shouldFailWhenUserRoleMissing() {
        when(authUserRepository.existsByEmail("john@example.com")).thenReturn(false);
        when(roleRepository.findByName("USER")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> registrationService.register(createDto()))
                .isInstanceOf(IllegalStateException.class);

        verify(authUserRepository, never()).saveAndFlush(any());
    }
}
