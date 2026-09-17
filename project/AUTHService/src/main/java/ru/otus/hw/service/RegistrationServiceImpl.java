package ru.otus.hw.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.otus.hw.dto.RegisterResponseDto;
import ru.otus.hw.dto.UserAddressDto;
import ru.otus.hw.dto.UserCreateDto;
import ru.otus.hw.dto.UserCreatedEvent;
import ru.otus.hw.exception.DuplicateResourceException;
import ru.otus.hw.models.AuthUser;
import ru.otus.hw.models.Role;
import ru.otus.hw.producer.UserCreatedEventPublisher;
import ru.otus.hw.repository.AuthUserRepository;
import ru.otus.hw.repository.RoleRepository;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Регистрация: AuthService сохраняет credentials (email + password_hash + роль USER)
 * и через свой Outbox публикует расширенный {@link UserCreatedEvent} - потребители
 * (USERService - профиль/адреса/проекция, BILLINGService - счёт) создают свои данные асинхронно.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RegistrationServiceImpl implements RegistrationService {

    private static final String DEFAULT_ROLE = "USER";

    private static final String METRIC_REGISTRATIONS = "auth.registrations";

    private final AuthUserRepository authUserRepository;

    private final RoleRepository roleRepository;

    private final PasswordEncoder passwordEncoder;

    private final UserCreatedEventPublisher userCreatedEventPublisher;

    private final MeterRegistry meterRegistry;

    @Override
    @Transactional
    public RegisterResponseDto register(@NonNull UserCreateDto userCreateDto) {
        String email = userCreateDto.email().toLowerCase();
        if (authUserRepository.existsByEmail(email)) {
            throw new DuplicateResourceException("User with email '%s' already exists".formatted(email));
        }

        Role userRole = roleRepository.findByName(DEFAULT_ROLE)
                .orElseThrow(() -> new IllegalStateException("Default USER role not found in database"));

        Set<Role> roles = new HashSet<>();
        roles.add(userRole);

        AuthUser authUser = AuthUser.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(userCreateDto.password()))
                .roles(roles)
                .build();
        AuthUser savedUser = authUserRepository.saveAndFlush(authUser);
        log.info("Credentials saved for userId={}, email={}", savedUser.getId(), email);

        Counter.builder(METRIC_REGISTRATIONS)
                .description("Successful user registrations")
                .register(meterRegistry)
                .increment();

        userCreatedEventPublisher.send(buildUserCreatedEvent(savedUser, userCreateDto));

        return new RegisterResponseDto(savedUser.getId(), savedUser.getEmail());
    }

    /**
     * Расширенный UserCreatedEvent: профильные поля и адреса для USERService;
     * BILLING использует только {@code userId} (лишние поля игнорирует).
     * {@code eventId} детерминирован от userId - идемпотентность повторных записей outbox.
     */
    private UserCreatedEvent buildUserCreatedEvent(@NonNull AuthUser savedUser,
            @NonNull UserCreateDto userCreateDto) {
        List<UserAddressDto> addressDtos = userCreateDto.addresses() == null
                ? List.of()
                : userCreateDto.addresses().stream()
                        .map(address -> UserAddressDto.builder()
                                .fullAddress(address.fullAddress())
                                .city(address.city())
                                .postalCode(address.postalCode())
                                .isDefault(address.isDefault())
                                .deliveryPreferences(address.deliveryPreferences())
                                .build())
                        .toList();

        return UserCreatedEvent.builder()
                .eventId(deterministicEventId(savedUser.getId()))
                .userId(savedUser.getId())
                .email(savedUser.getEmail())
                .userName(userCreateDto.userName())
                .firstName(userCreateDto.firstName())
                .lastName(userCreateDto.lastName())
                .phone(userCreateDto.phone())
                .addresses(addressDtos)
                .timestamp(Instant.now())
                .build();
    }

    private String deterministicEventId(@NonNull Long userId) {
        return UUID.nameUUIDFromBytes(("user-created:" + userId).getBytes(StandardCharsets.UTF_8)).toString();
    }
}
