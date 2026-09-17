package ru.otus.hw.security;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import ru.otus.hw.config.properties.AppProperties;

import java.time.Duration;
import java.util.List;

/**
 * Аутентификация по схеме HTTP Basic (email + пароль) для отладки в Swagger-UI.
 *
 * <p>В WAREHOUSEService нет собственной таблицы пользователей, поэтому пара логин/пароль
 * валидируется публичным {@code POST {authServiceUrl}/api/v1/auth/login} запущенного
 * AUTHService (порт 8006). Полученный {@code accessToken} парсится локально общим
 * {@link JwtTokenProvider} в {@link AuthPrincipal} - тот же principal, что и при
 * обычном JWT-флоу, поэтому {@code @PreAuthorize} и
 * {@code @AuthenticationPrincipal AuthPrincipal} продолжают работать без изменений.
 *
 * <p>Не является Spring-компонентом: экземпляр создаётся в {@code SecurityConfig}
 * (цепочка №2) по аналогии с {@link JwtAuthenticationFilter}.
 */
@Slf4j
public class AuthServiceBasicAuthProvider implements AuthenticationProvider {

    private static final String AUTH_LOGIN_PATH = "/api/v1/auth/login";

    private static final Duration AUTH_SERVICE_TIMEOUT = Duration.ofSeconds(5);

    private final RestClient restClient;

    private final JwtTokenProvider jwtTokenProvider;

    public AuthServiceBasicAuthProvider(@NonNull AppProperties appProperties,
            @NonNull JwtTokenProvider jwtTokenProvider) {
        this(buildRestClient(appProperties.getAuthServiceUrl()), jwtTokenProvider);
    }

    AuthServiceBasicAuthProvider(@NonNull RestClient restClient, @NonNull JwtTokenProvider jwtTokenProvider) {
        this.restClient = restClient;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        String email = authentication.getName();
        String password = String.valueOf(authentication.getCredentials());

        LoginResponse loginResponse = loginViaAuthService(email, password);

        AuthPrincipal principal = parseAccessToken(loginResponse.accessToken());

        List<SimpleGrantedAuthority> authorities = principal.roles().stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
        return new UsernamePasswordAuthenticationToken(principal, null, authorities);
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }

    private LoginResponse loginViaAuthService(String email, String password) {
        try {
            return restClient.post()
                    .uri(AUTH_LOGIN_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new LoginRequest(email, password))
                    .retrieve()
                    .body(LoginResponse.class);
        } catch (RestClientResponseException e) {
            log.debug("AUTHService rejected credentials with status {}", e.getStatusCode().value());
            throw new BadCredentialsException("Invalid email or password");
        } catch (RestClientException e) {
            log.warn("AUTHService is unavailable during HTTP Basic authentication: {}", e.getMessage());
            throw new AuthenticationServiceException("AUTHService is unavailable", e);
        }
    }

    private AuthPrincipal parseAccessToken(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            throw new AuthenticationServiceException("AUTHService returned an empty access token");
        }
        try {
            return jwtTokenProvider.parseAndVerify(accessToken);
        } catch (RuntimeException e) {
            log.warn("AUTHService returned an invalid access token: {}", e.getMessage());
            throw new AuthenticationServiceException("Invalid access token from AUTHService", e);
        }
    }

    private static RestClient buildRestClient(String baseUrl) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(AUTH_SERVICE_TIMEOUT);
        requestFactory.setReadTimeout(AUTH_SERVICE_TIMEOUT);
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    private record LoginRequest(String email, String password) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record LoginResponse(String accessToken) {
    }
}
