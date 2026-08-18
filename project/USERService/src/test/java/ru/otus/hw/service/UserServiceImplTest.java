package ru.otus.hw.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;
import ru.otus.hw.config.properties.SimulationConfig;
import ru.otus.hw.dto.UserCreateDto;
import ru.otus.hw.dto.UserCreatedEvent;
import ru.otus.hw.dto.UserResponseDto;
import ru.otus.hw.dto.mapper.UserMapper;
import ru.otus.hw.exception.DuplicateResourceException;
import ru.otus.hw.models.AccountStatus;
import ru.otus.hw.models.Role;
import ru.otus.hw.models.User;
import ru.otus.hw.producer.NotificationEventPublisher;
import ru.otus.hw.producer.UserCreatedEventPublisher;
import ru.otus.hw.repository.RoleRepository;
import ru.otus.hw.repository.UserRepository;

import java.util.HashSet;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Регистрация пользователя после перехода на событийную хореографию USER → BILLING:
 * пользователь сохраняется с PENDING, UserCreatedEvent записывается в outbox в той же
 * транзакции, синхронного вызова биллинга нет; сбои публикуют USER_CREATION_FAILED.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserServiceImplTest {

    private static final UserCreateDto CREATE_DTO =
            new UserCreateDto("johndoe", "John", "Doe", "john@example.com", "Password123!");

    private static final Long SAVED_USER_ID = 7L;

    @Mock
    private UserRepository userRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private UserMapper mapper;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private SimulationConfig simulationConfig;

    @Mock
    private UserCreatedEventPublisher userCreatedEventPublisher;

    @Mock
    private NotificationEventPublisher notificationEventPublisher;

    @InjectMocks
    private UserServiceImpl userService;

    @BeforeEach
    void setUp() {
        when(simulationConfig.isSimulationErrorsBypassEnabled()).thenReturn(true);
        User mappedUser = new User();
        mappedUser.setEmail(CREATE_DTO.email());
        when(mapper.toEntity(CREATE_DTO)).thenReturn(mappedUser);
        when(passwordEncoder.encode(CREATE_DTO.password())).thenReturn("encoded-password");
        when(roleRepository.findByName("USER")).thenReturn(Optional.of(new Role("USER", "default", new HashSet<>())));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(SAVED_USER_ID);
            return user;
        });
        when(mapper.toUserResponseDto(any(User.class))).thenReturn(
                new UserResponseDto(SAVED_USER_ID, "johndoe", "John", "Doe", "john@example.com", "PENDING"));
    }

    @Test
    @DisplayName("createUser: пользователь сохраняется с PENDING, outbox-запись NEW в той же транзакции, "
            + "синхронного биллинг-вызова нет, публикуется USER_CREATED")
    void shouldSavePendingUserAndAppendOutboxEvent() {
        when(userRepository.existsByUserName(CREATE_DTO.userName())).thenReturn(false);
        when(userRepository.existsByEmail(CREATE_DTO.email())).thenReturn(false);

        UserResponseDto response = userService.createUser(CREATE_DTO);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        User savedUser = userCaptor.getValue();
        assertThat(savedUser.getAccountStatus()).isEqualTo(AccountStatus.PENDING);
        assertThat(savedUser.getPassword()).isEqualTo("encoded-password");
        assertThat(savedUser.getRoles()).extracting(Role::getName).containsExactly("USER");

        ArgumentCaptor<UserCreatedEvent> eventCaptor = ArgumentCaptor.forClass(UserCreatedEvent.class);
        verify(userCreatedEventPublisher).send(eventCaptor.capture());
        UserCreatedEvent event = eventCaptor.getValue();
        assertThat(event.userId()).isEqualTo(SAVED_USER_ID);
        assertThat(event.email()).isEqualTo(CREATE_DTO.email());
        assertThat(event.eventId()).isNotBlank();
        assertThat(event.timestamp()).isNotNull();

        InOrder inOrder = inOrder(userRepository, userCreatedEventPublisher);
        inOrder.verify(userRepository).save(any(User.class));
        inOrder.verify(userCreatedEventPublisher).send(any(UserCreatedEvent.class));

        verify(notificationEventPublisher).publish(eq(SAVED_USER_ID), eq("USER_CREATED"),
                contains(CREATE_DTO.email()));

        assertThat(response.accountStatus()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("конфликт email: DuplicateResourceException ре-брошен, публикуется USER_CREATION_FAILED "
            + "с причиной, пользователь и outbox-запись не создаются")
    void shouldThrowAndNotifyOnEmailConflict() {
        when(userRepository.existsByUserName(CREATE_DTO.userName())).thenReturn(false);
        when(userRepository.existsByEmail(CREATE_DTO.email())).thenReturn(true);
        User existing = new User();
        existing.setId(99L);
        when(userRepository.findByEmail(CREATE_DTO.email())).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> userService.createUser(CREATE_DTO))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("email");

        verify(userRepository, never()).save(any(User.class));
        verify(userCreatedEventPublisher, never()).send(any(UserCreatedEvent.class));
        verify(notificationEventPublisher).publish(eq(99L), eq("USER_CREATION_FAILED"),
                contains("email"));
    }

    @Test
    @DisplayName("конфликт username: DuplicateResourceException ре-брошен, публикуется USER_CREATION_FAILED "
            + "с причиной и id существующего владельца username")
    void shouldThrowAndNotifyOnUserNameConflict() {
        when(userRepository.existsByUserName(CREATE_DTO.userName())).thenReturn(true);
        when(userRepository.existsByEmail(CREATE_DTO.email())).thenReturn(false);
        when(userRepository.findByEmail(CREATE_DTO.email())).thenReturn(Optional.empty());
        User existing = new User();
        existing.setId(100L);
        when(userRepository.findByProfileUserName(CREATE_DTO.userName())).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> userService.createUser(CREATE_DTO))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("username");

        verify(notificationEventPublisher).publish(eq(100L), eq("USER_CREATION_FAILED"),
                contains("username"));
    }

    @Test
    @DisplayName("валидационный сбой (нет email): USER_CREATION_FAILED публикуется с sentinel userId 0, "
            + "исключение ре-бросается")
    void shouldThrowAndNotifyOnValidationError() {
        UserCreateDto invalidDto = new UserCreateDto("johndoe", "John", "Doe", null, "Password123!");
        when(userRepository.findByProfileUserName("johndoe")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.createUser(invalidDto))
                .isInstanceOf(IllegalArgumentException.class);

        verify(userRepository, never()).save(any(User.class));
        verify(userCreatedEventPublisher, never()).send(any(UserCreatedEvent.class));
        verify(notificationEventPublisher).publish(eq(0L), eq("USER_CREATION_FAILED"), anyString());
    }
}
