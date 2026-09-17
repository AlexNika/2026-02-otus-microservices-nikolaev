package ru.otus.hw.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.otus.hw.exception.ErrorCodes;
import ru.otus.hw.models.Account;
import ru.otus.hw.repository.AccountRepository;
import ru.otus.hw.security.JwtTokenProvider;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Интеграционные тесты идемпотентного реплея BILLINGService на реальном контексте
 * (Testcontainers-Postgres с реальными уникальными индексами из миграций):
 * повторный депозит с тем же {@code idempotencyKey} и повторное списание с тем же
 * {@code orderId} должны возвращать 200 с исходной транзакцией без повторного
 * изменения баланса; тот же ключ/заказ с другой суммой - 409
 * {@code IDEMPOTENCY_KEY_CONFLICT}. Реплей-ветки читают ленивую
 * {@code Transaction.account} ВНЕ транзакции, поэтому без подключённого
 * {@code @EntityGraph} эти сценарии падают в 500 (моки юнит-тестов это не ловят).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class TransactionIdempotentReplayIntegrationTest {

    private static final String INTERNAL_API_KEY = "test-internal-api-key";

    private static final AtomicLong USER_SEQ = new AtomicLong(10_000);

    private static final AtomicLong ORDER_SEQ = new AtomicLong(900_000);

    @Container
    private static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.11");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void configureProperties(@NonNull DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.internal-api-key", () -> INTERNAL_API_KEY);
        registry.add("app.security.jwt-secret-key",
                () -> "billing-idempotent-replay-integration-test-secret-key-2026");
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
    }

    private void createAccount(long userId) {
        accountRepository.save(Account.builder()
                .userId(userId)
                .balance(BigDecimal.ZERO)
                .enabled(true)
                .locked(false)
                .build());
    }

    private String userToken(long userId) {
        return jwtTokenProvider.generateToken("user" + userId + "@example.com", userId, List.of("USER"));
    }

    private MvcResult deposit(long userId, String idempotencyKey, String amount) throws Exception {
        String body = """
                {"amount": %s, "idempotencyKey": "%s"}
                """.formatted(amount, idempotencyKey);
        return mockMvc.perform(post("/api/v1/account/{userId}/deposit", userId)
                        .header("Authorization", "Bearer " + userToken(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private MvcResult withdrawInternal(long userId, long orderId, String amount) throws Exception {
        String body = """
                {"userId": %d, "orderId": %d, "amount": %s}
                """.formatted(userId, orderId, amount);
        return mockMvc.perform(post("/internal/order/withdraw")
                        .header("X-Internal-API-Key", INTERNAL_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private JsonNode parse(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("Повтор депозита тем же idempotencyKey и суммой -> 200, та же транзакция, баланс не меняется")
    void depositReplayReturnsSameTransactionWithoutDoubleDeposit() throws Exception {
        long userId = USER_SEQ.incrementAndGet();
        createAccount(userId);
        String idempotencyKey = "dep-" + UUID.randomUUID();

        MvcResult first = deposit(userId, idempotencyKey, "100");
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        JsonNode firstJson = parse(first);
        assertThat(firstJson.get("newBalance").decimalValue()).isEqualByComparingTo(new BigDecimal("100"));

        MvcResult replay = deposit(userId, idempotencyKey, "100");
        assertThat(replay.getResponse().getStatus())
                .as("Идемпотентный повтор депозита должен вернуть 200, тело: %s",
                        replay.getResponse().getContentAsString())
                .isEqualTo(200);
        JsonNode replayJson = parse(replay);
        assertThat(replayJson.get("transactionId").asLong()).isEqualTo(firstJson.get("transactionId").asLong());
        assertThat(replayJson.get("newBalance").decimalValue())
                .as("Повторный депозит не должен начислять средства повторно")
                .isEqualByComparingTo(new BigDecimal("100"));
    }

    @Test
    @DisplayName("Тот же idempotencyKey с другой суммой -> 409 IDEMPOTENCY_KEY_CONFLICT")
    void depositReplayWithDifferentAmountReturnsConflict() throws Exception {
        long userId = USER_SEQ.incrementAndGet();
        createAccount(userId);
        String idempotencyKey = "dep-" + UUID.randomUUID();

        MvcResult first = deposit(userId, idempotencyKey, "100");
        assertThat(first.getResponse().getStatus()).isEqualTo(200);

        mockMvc.perform(post("/api/v1/account/{userId}/deposit", userId)
                        .header("Authorization", "Bearer " + userToken(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": 50, \"idempotencyKey\": \"%s\"}".formatted(idempotencyKey)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCodes.IDEMPOTENCY_KEY_CONFLICT));
    }

    @Test
    @DisplayName("Повтор списания тем же orderId и суммой -> 200, та же транзакция, баланс не меняется")
    void withdrawReplayReturnsSameTransactionWithoutDoubleWithdrawal() throws Exception {
        long userId = USER_SEQ.incrementAndGet();
        createAccount(userId);
        MvcResult funding = deposit(userId, "dep-" + UUID.randomUUID(), "100");
        assertThat(funding.getResponse().getStatus()).isEqualTo(200);

        long orderId = ORDER_SEQ.incrementAndGet();
        MvcResult first = withdrawInternal(userId, orderId, "30");
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        JsonNode firstJson = parse(first);
        assertThat(firstJson.get("newBalance").decimalValue()).isEqualByComparingTo(new BigDecimal("70"));

        MvcResult replay = withdrawInternal(userId, orderId, "30");
        assertThat(replay.getResponse().getStatus())
                .as("Идемпотентный повтор списания должен вернуть 200, тело: %s",
                        replay.getResponse().getContentAsString())
                .isEqualTo(200);
        JsonNode replayJson = parse(replay);
        assertThat(replayJson.get("transactionId").asLong()).isEqualTo(firstJson.get("transactionId").asLong());
        assertThat(replayJson.get("newBalance").decimalValue())
                .as("Повторное списание не должно списывать средства повторно")
                .isEqualByComparingTo(new BigDecimal("70"));
    }

    @Test
    @DisplayName("Тот же orderId с другой суммой -> 409 IDEMPOTENCY_KEY_CONFLICT")
    void withdrawReplayWithDifferentAmountReturnsConflict() throws Exception {
        long userId = USER_SEQ.incrementAndGet();
        createAccount(userId);
        MvcResult funding = deposit(userId, "dep-" + UUID.randomUUID(), "100");
        assertThat(funding.getResponse().getStatus()).isEqualTo(200);

        long orderId = ORDER_SEQ.incrementAndGet();
        MvcResult first = withdrawInternal(userId, orderId, "30");
        assertThat(first.getResponse().getStatus()).isEqualTo(200);

        MvcResult conflict = withdrawInternal(userId, orderId, "40");
        assertThat(conflict.getResponse().getStatus())
                .as("Списание по тому же orderId с другой суммой должно вернуть 409, тело: %s",
                        conflict.getResponse().getContentAsString())
                .isEqualTo(409);
        assertThat(parse(conflict).get("code").asText()).isEqualTo(ErrorCodes.IDEMPOTENCY_KEY_CONFLICT);
    }
}
