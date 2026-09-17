package ru.otus.hw.service;

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
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
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
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Выдача и ротация токенов AuthService: login -> пара токенов (в БД только SHA-256 хэш
 * refresh), refresh c ротацией, reuse detection (purge всех токенов юзера -> 401),
 * истёкший refresh -> 401, logout удаляет refresh из БД.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthenticationServiceTest {

    private static final Long USER_ID = 42L;

    private static final String EMAIL = "john@example.com";

    private static final String OLD_REFRESH = "old-refresh-uuid";

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private AuthUserRepository authUserRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private RefreshTokenProperties refreshTokenProperties;

    @InjectMocks
    private AuthenticationServiceImpl authenticationService;

    private static @NonNull AuthUser authUser() {
        Role role = new Role();
        role.setName("USER");
        AuthUser user = AuthUser.builder()
                .email(EMAIL)
                .passwordHash("$2a$12$hash")
                .roles(Set.of(role))
                .build();
        user.setId(USER_ID);
        return user;
    }

    private static @NonNull String sha256Hex(@NonNull String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static @NonNull RefreshToken storedToken(boolean rotated, LocalDateTime expiresAt) {
        RefreshToken token = RefreshToken.builder()
                .userId(USER_ID)
                .tokenHash(sha256Hex(OLD_REFRESH))
                .rotated(rotated)
                .expiresAt(expiresAt)
                .build();
        token.setId(1L);
        return token;
    }

    @Test
    @DisplayName("login: новая пара токенов; refresh хранится только как SHA-256 хэш")
    void shouldIssueTokenPairOnLogin() {
        Authentication authentication =
                new UsernamePasswordAuthenticationToken(EMAIL, "Password1!", List.of());
        when(authenticationManager.authenticate(any())).thenReturn(authentication);
        when(authUserRepository.findByEmail(EMAIL)).thenReturn(Optional.of(authUser()));
        when(refreshTokenProperties.getTtlDays()).thenReturn(30);
        when(jwtTokenProvider.generateToken(eq(EMAIL), eq(USER_ID), anyList())).thenReturn("access.jwt.token");

        AuthTokenResponseDto response = authenticationService.login(new LoginRequestDto(EMAIL, "Password1!"));

        assertThat(response.accessToken()).isEqualTo("access.jwt.token");
        assertThat(response.refreshToken()).isNotBlank();
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.userId()).isEqualTo(USER_ID);
        assertThat(response.roles()).containsExactly("USER");

        ArgumentCaptor<RefreshToken> tokenCaptor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(tokenCaptor.capture());
        RefreshToken saved = tokenCaptor.getValue();
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getTokenHash()).isEqualTo(sha256Hex(response.refreshToken()));
        assertThat(saved.getRotated()).isFalse();
        assertThat(saved.getExpiresAt()).isAfter(LocalDateTime.now().plusDays(29));
    }

    @Test
    @DisplayName("login: неверные credentials -> BadCredentialsException (401 в контроллере)")
    void shouldThrowOnBadCredentials() {
        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("Bad credentials"));

        assertThatThrownBy(() -> authenticationService.login(new LoginRequestDto(EMAIL, "wrong")))
                .isInstanceOf(BadCredentialsException.class);

        verify(refreshTokenRepository, never()).save(any());
    }

    @Test
    @DisplayName("refresh: ротация - старый токен помечается rotated=true и выдаётся новая пара")
    void shouldRotateTokensOnRefresh() {
        when(refreshTokenRepository.findByTokenHash(sha256Hex(OLD_REFRESH)))
                .thenReturn(Optional.of(storedToken(false, LocalDateTime.now().plusDays(10))));
        when(authUserRepository.findById(USER_ID)).thenReturn(Optional.of(authUser()));
        when(refreshTokenProperties.getTtlDays()).thenReturn(30);
        when(jwtTokenProvider.generateToken(eq(EMAIL), eq(USER_ID), anyList())).thenReturn("new.access.token");

        AuthTokenResponseDto response = authenticationService.refresh(OLD_REFRESH);

        assertThat(response.accessToken()).isEqualTo("new.access.token");
        assertThat(response.refreshToken()).isNotEqualTo(OLD_REFRESH);

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository, org.mockito.Mockito.atLeast(1)).save(captor.capture());
        assertThat(captor.getAllValues().get(0).getRotated()).isTrue();
        assertThat(captor.getAllValues().stream()
                .anyMatch(t -> t.getTokenHash().equals(sha256Hex(response.refreshToken())))).isTrue();
    }

    @Test
    @DisplayName("refresh reuse: предъявлен уже ротированный токен -> purge ВСЕХ токенов юзера и 401")
    void shouldPurgeAllTokensOnReuse() {
        when(refreshTokenRepository.findByTokenHash(sha256Hex(OLD_REFRESH)))
                .thenReturn(Optional.of(storedToken(true, LocalDateTime.now().plusDays(10))));

        assertThatThrownBy(() -> authenticationService.refresh(OLD_REFRESH))
                .isInstanceOf(InvalidRefreshTokenException.class)
                .hasMessageContaining("reuse");

        verify(refreshTokenRepository).deleteByUserId(USER_ID);
        verify(jwtTokenProvider, never()).generateToken(anyString(), any(), anyList());
    }

    @Test
    @DisplayName("refresh: истёкший токен -> удаление и 401")
    void shouldRejectExpiredRefreshToken() {
        when(refreshTokenRepository.findByTokenHash(sha256Hex(OLD_REFRESH)))
                .thenReturn(Optional.of(storedToken(false, LocalDateTime.now().minusSeconds(1))));

        assertThatThrownBy(() -> authenticationService.refresh(OLD_REFRESH))
                .isInstanceOf(InvalidRefreshTokenException.class)
                .hasMessageContaining("expired");

        verify(jwtTokenProvider, never()).generateToken(anyString(), any(), anyList());
    }

    @Test
    @DisplayName("refresh: неизвестный токен -> 401")
    void shouldRejectUnknownRefreshToken() {
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authenticationService.refresh("unknown-token"))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    @DisplayName("logout: предъявленный refresh удаляется из БД")
    void shouldDeleteRefreshTokenOnLogout() {
        RefreshToken token = storedToken(false, LocalDateTime.now().plusDays(10));
        when(refreshTokenRepository.findByTokenHash(sha256Hex(OLD_REFRESH))).thenReturn(Optional.of(token));

        authenticationService.logout(OLD_REFRESH);

        verify(refreshTokenRepository).delete(token);
    }

    @Test
    @DisplayName("logout: неизвестный refresh - тихий no-op")
    void shouldDoNothingOnLogoutWithUnknownToken() {
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        authenticationService.logout("unknown-token");

        verify(refreshTokenRepository, never()).delete(any());
    }

    @Test
    @DisplayName("refresh: пользователь для ротированного токена не найден -> 404 AuthUserNotFound")
    void shouldThrowWhenUserMissingOnRotation() {
        when(refreshTokenRepository.findByTokenHash(sha256Hex(OLD_REFRESH)))
                .thenReturn(Optional.of(storedToken(false, LocalDateTime.now().plusDays(10))));
        when(authUserRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authenticationService.refresh(OLD_REFRESH))
                .isInstanceOf(AuthUserNotFoundException.class);
    }
}
