package ru.otus.hw.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.otus.hw.config.SecurityConfig;
import ru.otus.hw.config.properties.AuthSecurityProperties;
import ru.otus.hw.controller.RoleResource;
import ru.otus.hw.dto.RoleDto;
import ru.otus.hw.service.AuthUserDetailsService;
import ru.otus.hw.service.RoleService;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Admin-операции AuthService (роли):<br>
 * - доступ только с ролью ADMIN, иначе 403;<br>
 * - без токена/пароля - 401.
 */
@WebMvcTest(RoleResource.class)
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtSecurityProperties.class, AuthSecurityProperties.class})
@TestPropertySource(properties = "app.security.jwt-secret-key=auth-security-mockmvc-test-secret-key-2026!!!")
class RoleResourceSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private RoleService roleService;

    @MockitoBean
    private AuthUserDetailsService authUserDetailsService;

    private String tokenFor(Long userId, String role) {
        return jwtTokenProvider.generateToken("user" + userId + "@example.com", userId, List.of(role));
    }

    private void stubAdminUserDetails() {
        when(authUserDetailsService.loadUserByUsername("admin@admin.com")).thenReturn(
                new User("admin@admin.com", new BCryptPasswordEncoder().encode("admin"),
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @Test
    @DisplayName("GET /api/v1/roles без JWT -> 401")
    void shouldReturn401WithoutToken() throws Exception {
        mockMvc.perform(get("/api/v1/roles"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("GET /api/v1/roles токеном USER -> 403")
    void shouldForbidRolesForRegularUser() throws Exception {
        mockMvc.perform(get("/api/v1/roles")
                        .header("Authorization", "Bearer " + tokenFor(2L, "USER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("GET /api/v1/roles токеном ADMIN -> 200")
    void shouldAllowRolesForAdmin() throws Exception {
        when(roleService.getAll()).thenReturn(List.of(
                new RoleDto(1L, "USER", "Default user role"),
                new RoleDto(2L, "ADMIN", "Administrator role with full access")));

        mockMvc.perform(get("/api/v1/roles")
                        .header("Authorization", "Bearer " + tokenFor(1L, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("USER"));
    }

    @Test
    @DisplayName("GET /api/v1/roles по HTTP Basic (email + password админа) -> 200")
    void shouldAllowRolesWithBasicAuth() throws Exception {
        stubAdminUserDetails();
        when(roleService.getAll()).thenReturn(List.of(
                new RoleDto(1L, "USER", "Default user role"),
                new RoleDto(2L, "ADMIN", "Administrator role with full access")));

        mockMvc.perform(get("/api/v1/roles")
                        .with(httpBasic("admin@admin.com", "admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    @DisplayName("GET /api/v1/roles по HTTP Basic с неверным паролем -> 401")
    void shouldReturn401WithWrongBasicPassword() throws Exception {
        stubAdminUserDetails();

        mockMvc.perform(get("/api/v1/roles")
                        .with(httpBasic("admin@admin.com", "wrong-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("GET /api/v1/roles по HTTP Basic для роли USER -> 403")
    void shouldForbidBasicAuthForRegularUser() throws Exception {
        when(authUserDetailsService.loadUserByUsername("user@example.com")).thenReturn(
                new User("user@example.com", new BCryptPasswordEncoder().encode("secret"),
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));

        mockMvc.perform(get("/api/v1/roles")
                        .with(httpBasic("user@example.com", "secret")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }
}
