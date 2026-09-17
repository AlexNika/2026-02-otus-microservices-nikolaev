package ru.otus.hw.security;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Модульные тесты общего аудитора {@link SecurityAuditorAware}:
 * чистый JUnit, тестовый {@code SecurityContext} устанавливается напрямую.
 */
class SecurityAuditorAwareTest {

    private final SecurityAuditorAware auditorAware = new SecurityAuditorAware();

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Пустой SecurityContext (RabbitMQ-консьюмеры, внутренние вызовы) -> \"system\"")
    void emptySecurityContextReturnsSystem() {
        SecurityContextHolder.clearContext();

        assertTrue(auditorAware.getCurrentAuditor().isPresent());
        assertEquals("system", auditorAware.getCurrentAuditor().get());
    }

    @Test
    @DisplayName("Анонимный токен на permitAll-путях -> \"system\", а не \"anonymousUser\"")
    void anonymousAuthenticationReturnsSystem() {
        var anonymous = new AnonymousAuthenticationToken(
                "anonymous-key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
        SecurityContextHolder.getContext().setAuthentication(anonymous);

        assertEquals("system", auditorAware.getCurrentAuditor().get());
    }

    @Test
    @DisplayName("JWT-запрос (в контексте AuthPrincipal с email) -> email из claim sub")
    void jwtAuthPrincipalReturnsEmail() {
        var principal = new AuthPrincipal(1L, "user@mail.ru", List.of("USER"));
        var authentication = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        assertEquals("user@mail.ru", auditorAware.getCurrentAuditor().get());
    }

    @Test
    @DisplayName("AuthPrincipal с пустым email -> фолбэк на authentication.getName()")
    void authPrincipalWithEmptyEmailFallsBackToName() {
        var principal = new AuthPrincipal(1L, "", List.of("USER"));
        var authentication = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        assertEquals(authentication.getName(), auditorAware.getCurrentAuditor().get());
    }

    @Test
    @DisplayName("Прочий аутентифицированный принципал (UserDetails) -> authentication.getName()")
    void userDetailsPrincipalReturnsUsername() {
        var userDetails = User.withUsername("admin")
                .password("secret")
                .roles("USER")
                .build();
        var authentication = new UsernamePasswordAuthenticationToken(
                userDetails, "secret", userDetails.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(authentication);

        assertEquals("admin", auditorAware.getCurrentAuditor().get());
    }

    @Test
    @DisplayName("Неаутентифицированный токен -> \"system\"")
    void unauthenticatedTokenReturnsSystem() {
        var authentication = UsernamePasswordAuthenticationToken.unauthenticated(
                new AuthPrincipal(1L, "user@mail.ru", List.of("USER")), null);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        assertEquals("system", auditorAware.getCurrentAuditor().get());
    }
}
