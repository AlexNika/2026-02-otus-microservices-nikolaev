package ru.otus.hw.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.otus.hw.config.SecurityConfig;
import ru.otus.hw.controller.UserProfileResource;
import ru.otus.hw.dto.UserProfileDto;
import ru.otus.hw.service.UserProfileService;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security-цепочки USERService: профиль - только по JWT, userId берётся из claims.
 */
@WebMvcTest(UserProfileResource.class)
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtSecurityProperties.class, OwnershipChecker.class})
@TestPropertySource(properties = "app.security.jwt-secret-key=user-security-mockmvc-test-secret-key-2026!!!")
class UserSecurityTest {

    private static final Long OWNER_ID = 7L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private UserProfileService profileService;

    private String tokenFor(Long userId, String role) {
        return jwtTokenProvider.generateToken("user" + userId + "@example.com", userId, List.of(role));
    }

    private UserProfileDto profile() {
        return new UserProfileDto("johndoe", "John", "Doe",
                LocalDateTime.of(1990, 1, 1, 0, 0), "+79991234567", List.of());
    }

    @Test
    @DisplayName("GET /api/v1/profile без JWT -> 401 JSON ErrorDto")
    void shouldReturn401WithoutToken() throws Exception {
        mockMvc.perform(get("/api/v1/profile"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("GET /api/v1/profile со своим JWT -> 200 (userId из claims, без поиска по email)")
    void shouldReturnProfileForAuthenticatedUser() throws Exception {
        when(profileService.getProfile(eq(OWNER_ID))).thenReturn(profile());

        mockMvc.perform(get("/api/v1/profile")
                        .header("Authorization", "Bearer " + tokenFor(OWNER_ID, "USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userName").value("johndoe"))
                .andExpect(jsonPath("$.phone").value("+79991234567"));
    }

    @Test
    @DisplayName("битый JWT -> 401 Invalid or expired JWT token")
    void shouldReturn401OnInvalidToken() throws Exception {
        mockMvc.perform(get("/api/v1/profile")
                        .header("Authorization", "Bearer broken.jwt.token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid or expired JWT token"));
    }
}
