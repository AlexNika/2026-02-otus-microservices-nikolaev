package ru.otus.hw.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import ru.otus.hw.config.properties.AuthSecurityProperties;
import ru.otus.hw.config.properties.InternalApiKeyConfig;
import ru.otus.hw.security.AuthSecurityDefaults;
import ru.otus.hw.security.InternalApiKeyAuthFilter;
import ru.otus.hw.security.JwtAuthenticationFilter;
import ru.otus.hw.security.JwtTokenProvider;
import ru.otus.hw.service.AuthUserDetailsService;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Безопасность AuthService (единственный Issuer токенов):
 * <ul>
 *   <li>Цепочка №0 - {@code /internal/**}: статический ключ {@code X-Internal-API-Key},
 *       фильтр встроен в цепочку (не зависит от порядка servlet-фильтров);</li>
 *   <li>Цепочка №1 - {@code /actuator/**}: permitAll (health/metrics вне JWT);</li>
 *   <li>Цепочка №2 - всё остальное: swagger и {@code /api/v1/auth/register|login|refresh}
 *       permitAll, остальное authenticated: JWT-токен либо HTTP Basic
 *       (email + password, для Swagger-UI и локальной разработки).</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtTokenProvider jwtTokenProvider;

    private final AuthSecurityProperties authSecurityProperties;

    private final ObjectMapper objectMapper;

    private final ObjectProvider<InternalApiKeyConfig> internalApiKeyConfigProvider;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(authSecurityProperties.getBcryptIterations());
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

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
     * Основная цепочка: JWT либо HTTP Basic (email + password).
     * {@link DaoAuthenticationProvider} для Basic создаётся локально, а не общим бином -
     * чтобы не влиять на сборку глобального AuthenticationManager из
     * {@link AuthenticationConfiguration}.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
            AuthUserDetailsService authUserDetailsService,
            PasswordEncoder passwordEncoder) throws Exception {
        DaoAuthenticationProvider basicAuthenticationProvider =
                new DaoAuthenticationProvider(authUserDetailsService);
        basicAuthenticationProvider.setPasswordEncoder(passwordEncoder);

        http
                .authenticationProvider(basicAuthenticationProvider)
                .httpBasic(basic -> basic
                        .authenticationEntryPoint(AuthSecurityDefaults.authenticationEntryPoint(objectMapper)))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(AuthSecurityDefaults.PUBLIC_SWAGGER_PATHS).permitAll()
                        .requestMatchers(
                                "/api/v1/auth/register",
                                "/api/v1/auth/login",
                                "/api/v1/auth/refresh").permitAll()
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
