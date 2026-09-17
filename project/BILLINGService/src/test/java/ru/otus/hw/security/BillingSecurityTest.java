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
import ru.otus.hw.controller.AccountResource;
import ru.otus.hw.dto.AccountResponseDto;
import ru.otus.hw.service.AccountService;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security-цепочки BILLINGService (публичный контур): 401 без Bearer,
 * 403 на чужой счёт, 200 на свой, ADMIN bypass. Внутренний контур покрыт
 * GlobalExceptionHandlerTest.
 */
@WebMvcTest(AccountResource.class)
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtSecurityProperties.class, OwnershipChecker.class,
        BillingAuthz.class})
@TestPropertySource(properties = "app.security.jwt-secret-key=billing-security-mockmvc-test-secret-key-2026")
class BillingSecurityTest {

    private static final Long OWNER_ID = 7L;

    private static final Long OTHER_USER_ID = 8L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private AccountService accountService;

    private String tokenFor(Long userId, String role) {
        return jwtTokenProvider.generateToken("user" + userId + "@example.com", userId, List.of(role));
    }

    private AccountResponseDto account(Long userId) {
        return new AccountResponseDto(55L, userId, new BigDecimal("1500.00"), true, false);
    }

    @Test
    @DisplayName("GET /api/v1/account/user/{userId} без JWT -> 401 JSON ErrorDto")
    void shouldReturn401WithoutToken() throws Exception {
        mockMvc.perform(get("/api/v1/account/user/{userId}", OWNER_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("GET /api/v1/account/user/{userId} своим токеном -> 200")
    void shouldAllowSelfAccess() throws Exception {
        when(accountService.getByUserId(eq(OWNER_ID))).thenReturn(account(OWNER_ID));

        mockMvc.perform(get("/api/v1/account/user/{userId}", OWNER_ID)
                        .header("Authorization", "Bearer " + tokenFor(OWNER_ID, "USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(OWNER_ID));
    }

    @Test
    @DisplayName("GET /api/v1/account/user/{userId} чужим токеном -> 403")
    void shouldForbidAnotherUserAccess() throws Exception {
        mockMvc.perform(get("/api/v1/account/user/{userId}", OWNER_ID)
                        .header("Authorization", "Bearer " + tokenFor(OTHER_USER_ID, "USER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("GET /api/v1/account/user/{userId} токеном ADMIN -> 200 (bypass)")
    void shouldAllowAdminAccess() throws Exception {
        when(accountService.getByUserId(eq(OWNER_ID))).thenReturn(account(OWNER_ID));

        mockMvc.perform(get("/api/v1/account/user/{userId}", OWNER_ID)
                        .header("Authorization", "Bearer " + tokenFor(99L, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(OWNER_ID));
    }

    @Test
    @DisplayName("GET /api/v1/account/{id}: свой счёт -> 200, чужой -> 403, ADMIN -> 200")
    void shouldApplyOwnershipOnGetById() throws Exception {
        AccountResponseDto ownerAccount = account(OWNER_ID);
        when(accountService.getById(55L)).thenReturn(ownerAccount);

        mockMvc.perform(get("/api/v1/account/55")
                        .header("Authorization", "Bearer " + tokenFor(OWNER_ID, "USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(OWNER_ID));

        mockMvc.perform(get("/api/v1/account/55")
                        .header("Authorization", "Bearer " + tokenFor(OTHER_USER_ID, "USER")))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/account/55")
                        .header("Authorization", "Bearer " + tokenFor(99L, "ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("битый JWT -> 401 Invalid or expired JWT token")
    void shouldReturn401OnInvalidToken() throws Exception {
        mockMvc.perform(get("/api/v1/account/user/{userId}", OWNER_ID)
                        .header("Authorization", "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid or expired JWT token"));
    }
}
