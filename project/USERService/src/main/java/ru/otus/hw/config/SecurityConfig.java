package ru.otus.hw.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import ru.otus.hw.config.properties.AppProperties;
import ru.otus.hw.config.properties.InternalApiKeyConfig;
import ru.otus.hw.security.AuthSecurityDefaults;
import ru.otus.hw.security.AuthServiceBasicAuthProvider;
import ru.otus.hw.security.InternalApiKeyAuthFilter;
import ru.otus.hw.security.JwtAuthenticationFilter;
import ru.otus.hw.security.JwtTokenProvider;

/**
 * Безопасность USERService на базе COMMONDomain (локальная stateless JWT-валидация):
 * <ul>
 *   <li>цепочка №0 - {@code /internal/**}: статический ключ {@code X-Internal-API-Key}
 *       встроен в цепочку (у сервиса нет internal-эндпоинтов, защита uniform);</li>
 *   <li>цепочка №1 - {@code /actuator/**}: permitAll;</li>
 *   <li>цепочка №2 - всё остальное: swagger permitAll, {@code /api/v1/profile/**}
 *       и прочие запросы authenticated, роли/ownership через {@code @PreAuthorize}.
 *       Помимо локальной валидации JWT поддерживается HTTP Basic (email + пароль)
 *       только для Swagger-отладки: логин/пароль валидируются запущенным AUTHService
 *       ({@code app.auth-service-url}), полученный JWT парсится локально.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtTokenProvider jwtTokenProvider;

    private final ObjectMapper objectMapper;

    private final ObjectProvider<InternalApiKeyConfig> internalApiKeyConfigProvider;

    private final ObjectProvider<AppProperties> appPropertiesProvider;

    @Bean
    @Order(0)
    public SecurityFilterChain internalSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/internal/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .addFilterBefore(
                        new InternalApiKeyAuthFilter(internalApiKeyConfigProvider, objectMapper),
                        AuthorizationFilter.class);

        return http.build();
    }

    @Bean
    @Order(1)
    public SecurityFilterChain actuatorSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher(AuthSecurityDefaults.PUBLIC_ACTUATOR_PATHS)
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());

        return http.build();
    }

    /**
     * Основная цепочка: {@code /api/v1/**} доступны по локальной валидации JWT либо
     * HTTP Basic (email + пароль, только для Swagger-отладки; валидация через запущенный
     * AUTHService). {@link ru.otus.hw.config.properties.AppProperties} разрешается через
     * {@link ObjectProvider} - он всегда присутствует в полном контексте приложения, но
     * отсутствует в {@code @WebMvcTest}-срезах, где HTTP Basic не требуется.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        AppProperties appProperties = appPropertiesProvider.getIfAvailable();
        if (appProperties != null) {
            http.authenticationProvider(new AuthServiceBasicAuthProvider(appProperties, jwtTokenProvider))
                    .httpBasic(basic -> basic
                            .authenticationEntryPoint(AuthSecurityDefaults.authenticationEntryPoint(objectMapper)));
        }
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(AuthSecurityDefaults.PUBLIC_SWAGGER_PATHS).permitAll()
                        .requestMatchers("/api/v1/profile/**").authenticated()
                        .anyRequest().authenticated()
                )
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(AuthSecurityDefaults.authenticationEntryPoint(objectMapper))
                        .accessDeniedHandler(AuthSecurityDefaults.accessDeniedHandler(objectMapper))
                )
                .addFilterBefore(new JwtAuthenticationFilter(jwtTokenProvider, objectMapper),
                        AuthorizationFilter.class);

        return http.build();
    }
}
