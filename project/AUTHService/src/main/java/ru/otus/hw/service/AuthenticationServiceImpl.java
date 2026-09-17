package ru.otus.hw.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.otus.hw.config.properties.RefreshTokenProperties;
import ru.otus.hw.dto.AuthTokenResponseDto;
import ru.otus.hw.dto.LoginRequestDto;
import ru.otus.hw.exception.AuthUserNotFoundException;
import ru.otus.hw.exception.InvalidRefreshTokenException;
import ru.otus.hw.models.AuthUser;
import ru.otus.hw.models.RefreshToken;
import ru.otus.hw.models.Role;
import ru.otus.hw.repository.AuthUserRepository;
import ru.otus.hw.repository.RefreshTokenRepository;
import ru.otus.hw.security.JwtTokenProvider;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Выдача и обслуживание токенов (единственный Issuer в системе):<br>
 * login/refresh/logout + генерация пары (access JWT 15 мин, opaque refresh 30 дней).
 *
 * <p>Refresh-токены:<br>
 * - в БД только SHA-256 хэш; ротация при каждом refresh
 * (старая строка {@code rotated=true} + новая запись);<br>
 * - reuse detection - предъявление уже ротированного хэша удаляет ВСЕ refresh-токены пользователя (401);<br>
 * - logout - удаление refresh из БД.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthenticationServiceImpl implements AuthenticationService {

    private final AuthenticationManager authenticationManager;

    private final AuthUserRepository authUserRepository;

    private final RefreshTokenRepository refreshTokenRepository;

    private final JwtTokenProvider jwtTokenProvider;

    private final RefreshTokenProperties refreshTokenProperties;

    /**
     * Логин по credentials (AuthenticationManager + BCrypt): выдаёт новую пару токенов.
     */
    @Override
    @Transactional
    public AuthTokenResponseDto login(@NonNull LoginRequestDto loginRequest) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(loginRequest.email(), loginRequest.password()));

        AuthUser authUser = authUserRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new AuthUserNotFoundException(
                        "User with e-mail '%s' not found".formatted(authentication.getName())));

        log.info("User authenticated: userId={}, email={}", authUser.getId(), authUser.getEmail());
        return issueTokens(authUser);
    }

    /**
     * Refresh с ротацией: предъявленный токен помечается {@code rotated=true},
     * выдаётся новая пара. Предъявление уже ротированного токена (reuse) -
     * удаляются ВСЕ refresh-токены пользователя, возвращается 401.
     */
    @Override
    @Transactional
    public AuthTokenResponseDto refresh(@NonNull String refreshToken) {
        String tokenHash = sha256Hex(refreshToken);
        RefreshToken stored = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new InvalidRefreshTokenException("Invalid refresh token"));

        if (Boolean.TRUE.equals(stored.getRotated())) {
            long deleted = refreshTokenRepository.deleteByUserId(stored.getUserId());
            log.warn("Refresh token reuse detected for userId={}: all {} refresh token(s) revoked",
                    stored.getUserId(), deleted);
            throw new InvalidRefreshTokenException("Refresh token reuse detected: all tokens revoked");
        }

        if (stored.getExpiresAt().isBefore(LocalDateTime.now())) {
            refreshTokenRepository.delete(stored);
            throw new InvalidRefreshTokenException("Refresh token expired");
        }

        stored.setRotated(Boolean.TRUE);
        refreshTokenRepository.save(stored);

        AuthUser authUser = authUserRepository.findById(stored.getUserId())
                .orElseThrow(() -> new AuthUserNotFoundException(
                        "User with id '%s' not found".formatted(stored.getUserId())));

        return issueTokens(authUser);
    }

    /**
     * Logout: удаление предъявленного refresh-токена из БД (короткоживущий access
     * действует до истечения своего TTL - принято в обмен на stateless-валидацию).
     */
    @Override
    @Transactional
    public void logout(@NonNull String refreshToken) {
        refreshTokenRepository.findByTokenHash(sha256Hex(refreshToken)).ifPresent(token -> {
            refreshTokenRepository.delete(token);
            log.info("Refresh token removed for userId={}", token.getUserId());
        });
    }

    /**
     * Генерация пары: access JWT (claims sub/userId/roles/iat/exp) + opaque refresh
     * (persist SHA-256 хэша, TTL из {@code app.refresh.ttl-days}).
     */
    private AuthTokenResponseDto issueTokens(@NonNull AuthUser authUser) {
        List<String> roleNames = authUser.getRoles().stream()
                .map(Role::getName)
                .toList();

        String accessToken = jwtTokenProvider.generateToken(authUser.getEmail(), authUser.getId(), roleNames);

        String refreshTokenValue = UUID.randomUUID().toString();
        RefreshToken refreshTokenEntity = RefreshToken.builder()
                .userId(authUser.getId())
                .tokenHash(sha256Hex(refreshTokenValue))
                .rotated(Boolean.FALSE)
                .expiresAt(LocalDateTime.now().plusDays(refreshTokenProperties.getTtlDays()))
                .build();
        refreshTokenRepository.save(refreshTokenEntity);

        return new AuthTokenResponseDto(accessToken, refreshTokenValue,
                authUser.getId(), authUser.getEmail(), roleNames);
    }

    private static @NonNull String sha256Hex(@NonNull String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm is not available", e);
        }
    }
}
