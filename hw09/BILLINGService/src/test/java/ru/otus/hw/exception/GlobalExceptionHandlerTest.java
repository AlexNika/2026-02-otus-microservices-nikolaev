package ru.otus.hw.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.otus.hw.config.properties.InternalApiKeyConfig;
import ru.otus.hw.controller.InternalOrderResource;
import ru.otus.hw.service.AccountService;

import java.math.BigDecimal;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(InternalOrderResource.class)
class GlobalExceptionHandlerTest {

    private static final String VALID_API_KEY = "testInternalApiKey";

    private static final String WITHDRAW_URL = "/internal/order/withdraw";

    private static final String REFUND_URL = "/internal/order/refund";

    private static final String VALID_WITHDRAW_BODY = "{\"userId\":1,\"orderId\":100,\"amount\":1500.00}";

    private static final String VALID_REFUND_BODY = "{\"userId\":1,\"orderId\":100,\"amount\":1500.00}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InternalApiKeyConfig internalApiKeyConfig;

    @MockitoBean
    private AccountService accountService;

    @Test
    @DisplayName("должен вернуть 409 с кодом BILLING_INSUFFICIENT_FUNDS при недостатке средств")
    void shouldReturn409WithInsufficientFundsCode() throws Exception {
        when(internalApiKeyConfig.getInternalApiKey()).thenReturn(VALID_API_KEY);
        when(accountService.withdrawFromAccount(any(), any(), any()))
                .thenThrow(BillingOperationException.insufficientFunds(1L,
                        new BigDecimal("100.00"), new BigDecimal("1500.00")));

        mockMvc.perform(post(WITHDRAW_URL)
                        .header("X-Internal-API-Key", VALID_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_WITHDRAW_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value(ErrorCodes.BILLING_INSUFFICIENT_FUNDS))
                .andExpect(jsonPath("$.message")
                        .value("Insufficient funds for userId: 1, balance: 100.00, required: 1500.00"));
    }

    @Test
    @DisplayName("должен вернуть 409 с кодом BILLING_ACCOUNT_INACTIVE при неактивном счёте")
    void shouldReturn409WithAccountInactiveCode() throws Exception {
        when(internalApiKeyConfig.getInternalApiKey()).thenReturn(VALID_API_KEY);
        when(accountService.withdrawFromAccount(any(), any(), any()))
                .thenThrow(BillingOperationException.accountInactive(1L));

        mockMvc.perform(post(WITHDRAW_URL)
                        .header("X-Internal-API-Key", VALID_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_WITHDRAW_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value(ErrorCodes.BILLING_ACCOUNT_INACTIVE))
                .andExpect(jsonPath("$.message")
                        .value("Account is inactive (disabled or locked) for userId: 1"));
    }

    @Test
    @DisplayName("должен вернуть 400 с кодом BILLING_INVALID_AMOUNT при невалидной сумме")
    void shouldReturn400WithInvalidAmountCode() throws Exception {
        when(internalApiKeyConfig.getInternalApiKey()).thenReturn(VALID_API_KEY);
        when(accountService.refundToAccount(any(), any(), any()))
                .thenThrow(BillingOperationException.invalidAmount(new BigDecimal("-1500.00")));

        mockMvc.perform(post(REFUND_URL)
                        .header("X-Internal-API-Key", VALID_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REFUND_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value(ErrorCodes.BILLING_INVALID_AMOUNT))
                .andExpect(jsonPath("$.message")
                        .value("Invalid amount: -1500.00 (must be greater than 0)"));
    }

    @Test
    @DisplayName("должен вернуть 404 с кодом BILLING_ACCOUNT_NOT_FOUND при NotFoundException")
    void shouldReturn404WithAccountNotFoundCodeOnNotFoundException() throws Exception {
        when(internalApiKeyConfig.getInternalApiKey()).thenReturn(VALID_API_KEY);
        when(accountService.withdrawFromAccount(any(), any(), any()))
                .thenThrow(new NotFoundException("Account not found for userId: 1"));

        mockMvc.perform(post(WITHDRAW_URL)
                        .header("X-Internal-API-Key", VALID_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_WITHDRAW_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value(ErrorCodes.BILLING_ACCOUNT_NOT_FOUND))
                .andExpect(jsonPath("$.message").value("Account not found for userId: 1"));
    }

    @Test
    @DisplayName("должен вернуть 404 с кодом BILLING_ACCOUNT_NOT_FOUND при BillingOperationException.accountNotFound")
    void shouldReturn404WithAccountNotFoundCodeOnBillingOperationException() throws Exception {
        when(internalApiKeyConfig.getInternalApiKey()).thenReturn(VALID_API_KEY);
        when(accountService.withdrawFromAccount(any(), any(), any()))
                .thenThrow(BillingOperationException.accountNotFound(1L));

        mockMvc.perform(post(WITHDRAW_URL)
                        .header("X-Internal-API-Key", VALID_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_WITHDRAW_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value(ErrorCodes.BILLING_ACCOUNT_NOT_FOUND));
    }

    @Test
    @DisplayName("должен вернуть 409 с кодом CONCURRENT_MODIFICATION при OptimisticLockingFailureException")
    void shouldReturn409WithConcurrentModificationCode() throws Exception {
        when(internalApiKeyConfig.getInternalApiKey()).thenReturn(VALID_API_KEY);
        when(accountService.withdrawFromAccount(any(), any(), any()))
                .thenThrow(new OptimisticLockingFailureException("Row was updated or deleted by another transaction"));

        mockMvc.perform(post(WITHDRAW_URL)
                        .header("X-Internal-API-Key", VALID_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_WITHDRAW_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value(ErrorCodes.CONCURRENT_MODIFICATION))
                .andExpect(jsonPath("$.message")
                        .value("Concurrent modification detected, retry the operation"));
    }

    @Test
    @DisplayName("должен вернуть 409 с кодом DATA_INTEGRITY_VIOLATION при DataIntegrityViolationException")
    void shouldReturn409WithDataIntegrityViolationCode() throws Exception {
        when(internalApiKeyConfig.getInternalApiKey()).thenReturn(VALID_API_KEY);
        when(accountService.withdrawFromAccount(any(), any(), any()))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

        mockMvc.perform(post(WITHDRAW_URL)
                        .header("X-Internal-API-Key", VALID_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_WITHDRAW_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value(ErrorCodes.DATA_INTEGRITY_VIOLATION))
                .andExpect(jsonPath("$.message").value("Data integrity violation"));
    }

    @Test
    @DisplayName("должен вернуть 400 с кодом MALFORMED_REQUEST при битом JSON в теле запроса")
    void shouldReturn400WithMalformedRequestCodeOnInvalidJson() throws Exception {
        when(internalApiKeyConfig.getInternalApiKey()).thenReturn(VALID_API_KEY);

        mockMvc.perform(post(WITHDRAW_URL)
                        .header("X-Internal-API-Key", VALID_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                // language=text
                                "{invalid json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value(ErrorCodes.MALFORMED_REQUEST))
                .andExpect(jsonPath("$.message").value("Malformed request body"));
    }

    @Test
    @DisplayName("должен вернуть 400 при невалидном теле запроса (MethodArgumentNotValidException)")
    void shouldReturn400OnInvalidRequestBody() throws Exception {
        when(internalApiKeyConfig.getInternalApiKey()).thenReturn(VALID_API_KEY);

        mockMvc.perform(post(WITHDRAW_URL)
                        .header("X-Internal-API-Key", VALID_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":1,\"orderId\":100,\"amount\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message", containsString("Validation failed")))
                .andExpect(jsonPath("$.message", containsString("amount")))
                .andExpect(jsonPath("$.code").doesNotExist());
    }

    @Test
    @DisplayName("должен вернуть 401, если заголовок X-Internal-API-Key не задан")
    void shouldReturn401WhenApiKeyHeaderIsMissing() throws Exception {
        when(internalApiKeyConfig.getInternalApiKey()).thenReturn(VALID_API_KEY);

        mockMvc.perform(post(WITHDRAW_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_WITHDRAW_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message", containsString("Missing required header 'X-Internal-API-Key'")));
    }

    @Test
    @DisplayName("должен вернуть 401, если API-ключ неверный")
    void shouldReturn401WhenApiKeyIsInvalid() throws Exception {
        when(internalApiKeyConfig.getInternalApiKey()).thenReturn(VALID_API_KEY);

        mockMvc.perform(post(WITHDRAW_URL)
                        .header("X-Internal-API-Key", "wrongKey")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_WITHDRAW_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message", containsString("Invalid internal API key")));
    }
}
