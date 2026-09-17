package ru.otus.hw.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.otus.hw.dto.AuthUserDto;
import ru.otus.hw.exception.AuthUserNotFoundException;
import ru.otus.hw.models.AuthUser;
import ru.otus.hw.repository.AuthUserRepository;
import ru.otus.hw.repository.RefreshTokenRepository;

import java.util.Collection;
import java.util.List;

/**
 * Админ-операции над credentials-записями (список/поиск/удаление):
 * только чтение и удаление auth_users (+ каскадное удаление refresh-токенов),
 * password_hash наружу не отдаётся. Профильные CRUD остаются в USERService.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CredentialUserServiceImpl implements CredentialUserService {

    private final AuthUserRepository authUserRepository;

    private final RefreshTokenRepository refreshTokenRepository;

    @Override
    @Transactional(readOnly = true)
    public Page<AuthUserDto> findAllUsers(@NonNull Pageable pageable) {
        return authUserRepository.findAll(pageable).map(this::toDto);
    }

    @Override
    @Transactional(readOnly = true)
    public AuthUserDto findUserById(@NonNull Long id) {
        return authUserRepository.findById(id)
                .map(this::toDto)
                .orElseThrow(() -> new AuthUserNotFoundException(
                        "User with id '%s' not found".formatted(id)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<AuthUserDto> findUsersByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return authUserRepository.findAllById(ids).stream()
                .map(this::toDto)
                .toList();
    }

    @Override
    @Transactional
    public void deleteUserById(@NonNull Long id) {
        AuthUser authUser = authUserRepository.findById(id)
                .orElseThrow(() -> new AuthUserNotFoundException(
                        "User with id '%s' not found".formatted(id)));
        refreshTokenRepository.deleteByUserId(id);
        authUserRepository.delete(authUser);
        log.info("Credentials removed for userId={}, email={}", id, authUser.getEmail());
        toDto(authUser);
    }

    @Override
    @Transactional
    public void deleteAllUsers() {
        refreshTokenRepository.deleteAllInBatch();
        authUserRepository.deleteAllInBatch();
        log.warn("All credentials removed");
    }

    private AuthUserDto toDto(@NonNull AuthUser authUser) {
        return new AuthUserDto(authUser.getId(), authUser.getEmail(), authUser.getCreated());
    }
}
