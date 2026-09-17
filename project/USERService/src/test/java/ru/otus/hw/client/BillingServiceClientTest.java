package ru.otus.hw.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import ru.otus.hw.exception.BillingServiceException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Read-only клиент BILLINGService: ключевая логика - onStatus-обработчик
 * (404 -> штатно false, прочие статусы -> BillingServiceException) и преобразование
 * сетевых сбоев в BillingServiceException с понятным сообщением. Проверяется
 * поведенчески через MockRestServiceServer, без ручной имитации RestClient-цепочки.
 */
class BillingServiceClientTest {

    private static final Long USER_ID = 12L;

    private static final String BASE_URL = "http://localhost:8001";

    private static final String ACCOUNT_URL = BASE_URL + "/internal/account/user/" + USER_ID;

    private MockRestServiceServer mockServer;

    private BillingServiceClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.baseUrl(BASE_URL).build();
        client = new BillingServiceClient(restClient);
    }

    @Test
    @DisplayName("200: биллинг-аккаунт есть - возвращает true, запрос идёт по внутреннему контуру")
    void shouldReturnTrueWhenAccountExists() {
        mockServer.expect(requestTo(ACCOUNT_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess());

        boolean exists = client.accountExists(USER_ID);

        assertThat(exists).isTrue();
        mockServer.verify();
    }

    @Test
    @DisplayName("404: биллинг-аккаунта нет - штатно возвращает false без исключения")
    void shouldReturnFalseWhenAccountMissing() {
        mockServer.expect(requestTo(ACCOUNT_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        boolean exists = client.accountExists(USER_ID);

        assertThat(exists).isFalse();
        mockServer.verify();
    }

    @Test
    @DisplayName("500: BillingServiceException с HTTP-статусом и userId в сообщении")
    void shouldThrowOnServerError() {
        mockServer.expect(requestTo(ACCOUNT_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> client.accountExists(USER_ID))
                .isInstanceOf(BillingServiceException.class)
                .hasMessageContaining("status=500")
                .hasMessageContaining(USER_ID.toString());

        mockServer.verify();
    }

    @Test
    @DisplayName("сбой соединения: BillingServiceException с текстом unavailable (connection refused)")
    void shouldThrowOnConnectionRefused() {
        mockServer.expect(requestTo(ACCOUNT_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(request -> {
                    throw new ConnectException("Connection refused");
                });

        assertThatThrownBy(() -> client.accountExists(USER_ID))
                .isInstanceOf(BillingServiceException.class)
                .hasMessageContaining("unavailable (connection refused)");

        mockServer.verify();
    }

    @Test
    @DisplayName("тайм-аут сокета: BillingServiceException с текстом timed out")
    void shouldThrowOnSocketTimeout() {
        mockServer.expect(requestTo(ACCOUNT_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(request -> {
                    throw new SocketTimeoutException("Read timed out");
                });

        assertThatThrownBy(() -> client.accountExists(USER_ID))
                .isInstanceOf(BillingServiceException.class)
                .hasMessageContaining("timed out");

        mockServer.verify();
    }

    @Test
    @DisplayName("прочий IOException: BillingServiceException с текстом Network error")
    void shouldThrowOnGenericIoError() {
        mockServer.expect(requestTo(ACCOUNT_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(request -> {
                    throw new IOException("boom");
                });

        assertThatThrownBy(() -> client.accountExists(USER_ID))
                .isInstanceOf(BillingServiceException.class)
                .hasMessageContaining("Network error");

        mockServer.verify();
    }
}
