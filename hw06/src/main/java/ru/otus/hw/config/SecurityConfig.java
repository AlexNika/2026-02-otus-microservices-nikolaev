package ru.otus.hw.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import ru.otus.hw.config.properties.BCryptConfig;
import ru.otus.hw.dto.ErrorDto;
import ru.otus.hw.security.JwtAuthenticationFilter;

import java.io.IOException;
import java.time.LocalDateTime;

@Slf4j
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    private final BCryptConfig bCryptConfig;

    private final ObjectMapper objectMapper;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(bCryptConfig.getBcryptIterations());
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    @Order(1)
    public SecurityFilterChain actuatorSecurityFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/actuator/**")
            .csrf(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());

        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**", "/swagger-resources/**").permitAll()
                .requestMatchers("/api/v1/auth/login", "/api/v1/auth/register").permitAll()
                .requestMatchers("/api/v1/profile/**").authenticated()
                .anyRequest().authenticated()
            )
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint((request, response, authException) -> {
                    String requestUri = request.getRequestURI();
                    log.warn("Security: Authentication required for '{}' due to: {}", requestUri,
                            authException.getClass().getSimpleName());
                    setErrorResponse(response, "Authentication required", HttpServletResponse.SC_UNAUTHORIZED,
                            true);
                })
                .accessDeniedHandler((request, response, accessDeniedException) -> {
                    String requestUri = request.getRequestURI();
                    Authentication auth = SecurityContextHolder.getContext().getAuthentication();

                    if (auth == null || !auth.isAuthenticated()) {
                        log.warn("Security: Access denied for '{}' — user not authenticated", requestUri);
                        setErrorResponse(response, "Authentication required", HttpServletResponse.SC_UNAUTHORIZED,
                                true);
                    } else {
                        log.warn("Security: Access denied for '{}' — authenticated user {} lacks permissions: {}",
                                requestUri,
                                auth.getName(),
                                accessDeniedException.getMessage());
                        setErrorResponse(response, "Access denied", HttpServletResponse.SC_FORBIDDEN, false);
                    }
                })
            )
                .addFilterBefore(jwtAuthenticationFilter, AuthorizationFilter.class);

        return http.build();
    }

    private void setErrorResponse(@NonNull HttpServletResponse response,
                                  String message,
                                  int statusCode,
                                  boolean addAuthHeader) {
        try {
            response.setStatus(statusCode);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            if (addAuthHeader && statusCode == HttpServletResponse.SC_UNAUTHORIZED) {
                response.setHeader("WWW-Authenticate", "Bearer realm=\"api\"");
            }

            ErrorDto errorDto = ErrorDto.builder()
                    .status(statusCode)
                    .message(message)
                    .timestamp(LocalDateTime.now())
                    .build();
            objectMapper.writeValue(response.getOutputStream(), errorDto);
            log.trace("Security: Error response written: status={}, message={}", statusCode, message);
        } catch (IOException e) {
            log.error("Security: Failed to write error response (status={}) — client may not receive JSON body",
                    statusCode, e);
        }
    }
}