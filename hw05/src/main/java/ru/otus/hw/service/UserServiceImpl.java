package ru.otus.hw.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.otus.hw.dto.UserCreateDto;
import ru.otus.hw.dto.UserPatchDto;
import ru.otus.hw.dto.UserResponseDto;
import ru.otus.hw.dto.UserUpdateDto;
import ru.otus.hw.dto.mapper.UserMapper;
import ru.otus.hw.exception.DuplicateResourceException;
import ru.otus.hw.exception.InternalServerErrorException;
import ru.otus.hw.exception.UserNotFoundException;
import ru.otus.hw.models.User;
import ru.otus.hw.repository.UserRepository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final Environment environment;
    private final UserRepository userRepository;
    private final UserMapper mapper;

    @Override
    @Transactional(readOnly = true)
    public Optional<UserResponseDto> findUserById(Long id) {
        if (isDevProfileActive()) {
            simulateRandomError();
        }
        return userRepository.findById(id).map(mapper::toUserResponseDto);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<UserResponseDto> findAllUsers(Pageable pageable) {
        if (isDevProfileActive()) {
            simulateRandomError();
        }
        log.info("Fetching users with pagination: page={}, size={}, sort={}",
                pageable.getPageNumber(), pageable.getPageSize(), pageable.getSort());
        return userRepository.findAll(pageable)
                .map(mapper::toUserResponseDto);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserResponseDto> findUsersByIds(Collection<Long> ids) {
        if (isDevProfileActive()) {
            simulateRandomError();
        }
        if (ids == null || ids.isEmpty()) {
            log.debug("Received empty or null list of user IDs for search");
            return List.of();
        }
        log.info("Searching for users by IDs: {}", ids);
        return userRepository.findByIdIn(ids).stream()
                .map(mapper::toUserResponseDto)
                .toList();
    }

    @Override
    @Transactional
    public UserResponseDto createUser(@NonNull UserCreateDto userCreateDto) {
        if (isDevProfileActive()) {
            simulateRandomError();
        }
        String userName = userCreateDto.userName();
        String email = userCreateDto.email();

        if (userName == null || email == null) {
            throw new IllegalArgumentException("Username and email must not be null");
        }

        List<String> createConflicts = collectCreateConflicts(userName, email);
        ensureNoConflicts(createConflicts, "create user");

        log.info("Creating user with username: {} and email: {}", userName, email);
        User user = mapper.toEntity(userCreateDto);
        User savedUser = userRepository.save(user);
        log.info("User created successfully with ID: {}", savedUser.getId());
        return mapper.toUserResponseDto(savedUser);
    }

    @Override
    @Transactional
    public UserResponseDto updateUserById(@NonNull Long id, @NonNull UserUpdateDto userUpdateDto) {
        if (isDevProfileActive()) {
            simulateRandomError();
        }
        User existingUser = userRepository.findById(id)
                .orElseThrow(() -> {
                    String msg = "User with id " + id + " not found while updating";
                    log.warn(msg);
                    return new UserNotFoundException(id);
                });

        List<String> updateConflicts = collectUpdateConflicts(existingUser, userUpdateDto.userName(),
                userUpdateDto.email());
        ensureNoConflicts(updateConflicts, "update user");

        log.info("Updating user with id: {}", id);
        mapper.updateUserFromDto(userUpdateDto, existingUser);
        User updatedUser = userRepository.save(existingUser);
        log.info("User updated successfully: {}", updatedUser.getId());

        return mapper.toUserResponseDto(updatedUser);
    }

    @Override
    @Transactional
    public UserResponseDto updateUserByIdPartially(@NonNull Long id, @NonNull UserPatchDto dto) {
        if (isDevProfileActive()) {
            simulateRandomError();
        }
        User existingUser = userRepository.findById(id)
                .orElseThrow(() -> {
                    String msg = "User with id " + id + " not found while patching";
                    log.warn(msg);
                    return new UserNotFoundException(id);
                });
        
        String effectiveUserName = dto.userName() != null ? dto.userName() : existingUser.getUserName();
        String effectiveEmail = dto.email() != null ? dto.email() : existingUser.getEmail();

        List<String> updateConflicts = collectUpdateConflicts(existingUser, effectiveUserName, effectiveEmail);
        ensureNoConflicts(updateConflicts, "patch user");

        log.info("Partially updating user with id: {}", id);
        mapper.partialUpdateFromDto(dto, existingUser);
        User savedUser = userRepository.save(existingUser);
        log.info("User partially updated successfully: {}", savedUser.getId());

        return mapper.toUserResponseDto(savedUser);
    }

    @Override
    @Transactional
    public void deleteUserById(long id) {
        if (isDevProfileActive()) {
            simulateRandomError();
        }
        if (!userRepository.existsById(id)) {
            log.info("User with id {} does not exist or already deleted; nothing to do!", id);
            return;
        }
        userRepository.deleteById(id);
        log.info("Deleting user with id: {}", id);
    }

    @Override
    @Transactional
    public void deleteAllUsers() {
        if (isDevProfileActive()) {
            simulateRandomError();
        }
        log.info("Deleting all users");
        userRepository.deleteAll();
        log.info("All users deleted successfully");
    }

    /**
     * Симулирует случайную внутреннюю ошибку сервера с вероятностью 10%.
     * Используется для демонстрации мониторинга и алертинга.
     * Работает только в профиле dev.
     */
    private void simulateRandomError() {
        if (ThreadLocalRandom.current().nextDouble() < 0.1) {
            throw new InternalServerErrorException("Random 500 error for monitoring demonstration");
        }
    }

    /**
     * Определяет, активен ли профиль dev.
     */
    private boolean isDevProfileActive() {
        for (String profile : environment.getActiveProfiles()) {
            if (profile.equals("dev")) {
                return true;
            }
        }
        return false;
    }

    private void ensureNoConflicts(@NonNull List<String> conflicts, String operation) {
        if (!conflicts.isEmpty()) {
            String errorMessage = "Cannot " + operation + ": " + String.join("; ", conflicts);
            log.warn(errorMessage);
            throw new DuplicateResourceException(errorMessage);
        }
    }

    private @NonNull List<String> collectCreateConflicts(@NonNull String userName,
                                                         @NonNull String email) {
        List<String> conflicts = new ArrayList<>();

        if (userRepository.existsByUserName(userName)) {
            conflicts.add("username '" + userName + "' is already taken");
        }

        if (userRepository.existsByEmail(email)) {
            conflicts.add("email '" + email + "' is already registered");
        }

        return conflicts;
    }

    private @NonNull List<String> collectUpdateConflicts(@NonNull User existingUser,
                                                         @NonNull String newUserName,
                                                         @NonNull String newEmail) {
        List<String> conflicts = new ArrayList<>();

        if (!existingUser.getUserName().equals(newUserName) &&
                userRepository.existsByUserName(newUserName)) {
            conflicts.add("username '" + newUserName + "' is already taken by another user");
        }

        if (!existingUser.getEmail().equals(newEmail) &&
                userRepository.existsByEmail(newEmail)) {
            conflicts.add("email '" + newEmail + "' is already registered by another user");
        }

        return conflicts;
    }
}
