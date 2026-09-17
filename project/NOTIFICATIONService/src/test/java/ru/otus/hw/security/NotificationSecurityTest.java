package ru.otus.hw.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.otus.hw.config.SecurityConfig;
import ru.otus.hw.controller.NotificationResource;
import ru.otus.hw.service.NotificationService;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security-цепочки NOTIFICATIONService: уведомления читает только их владелец или ADMIN;
 * {@code GET /api/v1/notification/all} доступен только ADMIN.
 */
@WebMvcTest(NotificationResource.class)
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtSecurityProperties.class, OwnershipChecker.class})
@TestPropertySource(properties = "app.security.jwt-secret-key=notification-security-mockmvc-test-secret-2026!")
class NotificationSecurityTest {

    private static final Long OWNER_ID = 7L;

    private static final Long OTHER_USER_ID = 8L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private NotificationService notificationService;

    private String tokenFor(Long userId, String role) {
        return jwtTokenProvider.generateToken("user" + userId + "@example.com", userId, List.of(role));
    }

    @Test
    @DisplayName("GET /api/v1/notification?userId=N без JWT -> 401")
    void shouldReturn401WithoutToken() throws Exception {
        mockMvc.perform(get("/api/v1/notification").param("userId", String.valueOf(OWNER_ID)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("GET своих уведомлений -> 200")
    void shouldAllowSelfAccess() throws Exception {
        when(notificationService.getNotificationsByUserId(eq(OWNER_ID))).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/notification")
                        .param("userId", String.valueOf(OWNER_ID))
                        .header("Authorization", "Bearer " + tokenFor(OWNER_ID, "USER")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET чужих уведомлений -> 403")
    void shouldForbidAnotherUserNotifications() throws Exception {
        mockMvc.perform(get("/api/v1/notification")
                        .param("userId", String.valueOf(OWNER_ID))
                        .header("Authorization", "Bearer " + tokenFor(OTHER_USER_ID, "USER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("GET любых уведомлений токеном ADMIN -> 200 (bypass)")
    void shouldAllowAdminAccess() throws Exception {
        when(notificationService.getNotificationsByUserId(eq(OWNER_ID))).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/notification")
                        .param("userId", String.valueOf(OWNER_ID))
                        .header("Authorization", "Bearer " + tokenFor(99L, "ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /api/v1/notification/all токеном USER -> 403")
    void shouldForbidNonAdminAllNotifications() throws Exception {
        mockMvc.perform(get("/api/v1/notification/all")
                        .header("Authorization", "Bearer " + tokenFor(OWNER_ID, "USER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("GET /api/v1/notification/all токеном ADMIN -> 200")
    void shouldAllowAdminAllNotifications() throws Exception {
        when(notificationService.getAllNotifications(any())).thenReturn(Page.empty());

        mockMvc.perform(get("/api/v1/notification/all")
                        .header("Authorization", "Bearer " + tokenFor(99L, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").value(0));
    }
}
