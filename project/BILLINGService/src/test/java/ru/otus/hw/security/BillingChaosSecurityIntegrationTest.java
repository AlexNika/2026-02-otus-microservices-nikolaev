package ru.otus.hw.security;

import de.codecentric.spring.boot.chaos.monkey.configuration.ChaosMonkeyProperties;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Интеграционные тесты доступа к хаос-эндпоинтам BILLINGService:
 * {@code /actuator/chaosmonkey**} - только JWT роли ADMIN
 * (401 без токена, 403 для не-админа, 200 для админа), остальной
 * actuator (health) остаётся открытым. Дополнительно проверяется,
 * что сервис стартует с выключенным Chaos Monkey (дефолт).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class BillingChaosSecurityIntegrationTest {

    @Container
    private static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.11");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private ChaosMonkeyProperties chaosMonkeyProperties;

    @DynamicPropertySource
    static void configureProperties(@NonNull DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.internal-api-key", () -> "test-internal-api-key");
        registry.add("app.security.jwt-secret-key",
                () -> "billing-chaos-security-integration-test-secret-key-2026");
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
        registry.add("management.endpoint.chaosmonkey.enabled", () -> "true");
    }

    private String tokenFor(Long userId, String role) {
        return jwtTokenProvider.generateToken("user" + userId + "@example.com", userId, List.of(role));
    }

    @Test
    @DisplayName("GET /actuator/chaosmonkey без токена -> 401")
    void shouldReturn401WithoutToken() throws Exception {
        mockMvc.perform(get("/actuator/chaosmonkey"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("GET /actuator/chaosmonkey токеном роли USER -> 403")
    void shouldReturn403ForUserRole() throws Exception {
        mockMvc.perform(get("/actuator/chaosmonkey")
                        .header("Authorization", "Bearer " + tokenFor(7L, "USER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("GET /actuator/chaosmonkey токеном ADMIN -> 200; хаос выключен по дефолту")
    void shouldAllowAdminAccess() throws Exception {
        mockMvc.perform(get("/actuator/chaosmonkey")
                        .header("Authorization", "Bearer " + tokenFor(99L, "ADMIN")))
                .andExpect(status().isOk());

        assertThat(chaosMonkeyProperties.isEnabled())
                .as("Chaos Monkey по умолчанию выключен")
                .isFalse();
    }

    @Test
    @DisplayName("GET /actuator/health без токена -> 200 (остальной actuator открыт)")
    void shouldKeepHealthOpen() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }
}
