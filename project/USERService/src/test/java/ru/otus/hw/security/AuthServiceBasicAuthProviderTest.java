package ru.otus.hw.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.ConnectException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Юнит-тест {@link AuthServiceBasicAuthProvider}: валидация логина/пароля выполняется
 * через {@code POST {authServiceUrl}/api/v1/auth/login} запущенного AUTHService
 * (имитируется {@link MockRestServiceServer}), а полученный токен парсится реальным
 * {@link JwtTokenProvider} с тестовым секретом.
 *
 * <ul>
 *   <li>успех - authenticated-токен с {@link AuthPrincipal} и {@code ROLE_*}-authorities;</li>
 *   <li>401 от AUTH - {@link BadCredentialsException};</li>
 *   <li>сбой сети - {@link AuthenticationServiceException}.</li>
 * </ul>
 */
class AuthServiceBasicAuthProviderTest {

    private static final String AUTH_SERVICE_URL = "http://auth-service:8006";

    private static final String AUTH_LOGIN_URL = AUTH_SERVICE_URL + "/api/v1/auth/login";

    private static final String EMAIL = "admin@admin.com";

    private static final String PASSWORD = "admin";

    private static final Long USER_ID = 7L;

    private MockRestServiceServer mockServer;

    private AuthServiceBasicAuthProvider provider;

    private JwtTokenProvider jwtTokenProvider;

    @BeforeEach
    void setUp() {
        JwtSecurityProperties jwtSecurityProperties = new JwtSecurityProperties();
        jwtSecurityProperties.setJwtSecretKey("user-basic-auth-provider-test-secret-key-2026");
        jwtTokenProvider = new JwtTokenProvider(jwtSecurityProperties);
        jwtTokenProvider.init();

        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.baseUrl(AUTH_SERVICE_URL).build();
        provider = new AuthServiceBasicAuthProvider(restClient, jwtTokenProvider);
    }

    private Authentication unauthenticatedToken() {
        return UsernamePasswordAuthenticationToken.unauthenticated(EMAIL, PASSWORD);
    }

    @Test
    @DisplayName("успех: из AUTH получен токен - возвращается AuthPrincipal с ROLE_*-authorities")
    void shouldAuthenticateOnSuccessfulLogin() {
        String accessToken = jwtTokenProvider.generateToken(EMAIL, USER_ID, List.of("USER", "ADMIN"));
        String responseBody = """
                {"accessToken":"%s","refreshToken":"rt","tokenType":"Bearer",\
                "userId":%d,"email":"%s","roles":["USER","ADMIN"]}\
                """.formatted(accessToken, USER_ID, EMAIL);

        mockServer.expect(requestTo(AUTH_LOGIN_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.email").value(EMAIL))
                .andExpect(jsonPath("$.password").value(PASSWORD))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        Authentication result = provider.authenticate(unauthenticatedToken());

        assertThat(result.isAuthenticated()).isTrue();
        assertThat(result.getPrincipal()).isInstanceOf(AuthPrincipal.class);
        AuthPrincipal principal = (AuthPrincipal) result.getPrincipal();
        assertThat(principal.userId()).isEqualTo(USER_ID);
        assertThat(principal.email()).isEqualTo(EMAIL);
        assertThat(principal.roles()).containsExactly("USER", "ADMIN");
        assertThat(result.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");

        mockServer.verify();
    }

    @Test
    @DisplayName("неверные логин/пароль: AUTH отвечает 401 - BadCredentialsException")
    void shouldThrowBadCredentialsWhenAuthRejects() {
        mockServer.expect(requestTo(AUTH_LOGIN_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> provider.authenticate(unauthenticatedToken()))
                .isInstanceOf(BadCredentialsException.class);

        mockServer.verify();
    }

    @Test
    @DisplayName("AUTH недоступен (сбой соединения): AuthenticationServiceException")
    void shouldThrowAuthenticationServiceExceptionWhenAuthUnavailable() {
        mockServer.expect(requestTo(AUTH_LOGIN_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(request -> {
                    throw new ConnectException("Connection refused");
                });

        assertThatThrownBy(() -> provider.authenticate(unauthenticatedToken()))
                .isInstanceOf(AuthenticationServiceException.class);

        mockServer.verify();
    }
}
