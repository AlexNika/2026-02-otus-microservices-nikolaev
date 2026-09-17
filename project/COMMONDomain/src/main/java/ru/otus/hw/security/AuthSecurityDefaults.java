package ru.otus.hw.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import ru.otus.hw.dto.ErrorDto;

import java.io.IOException;
import java.time.LocalDateTime;

/**
 * Общие константы и обработчики для сборки Security-цепочек микросервисов:
 * permitAll-пути (actuator, swagger) и единообразные JSON-ответы 401/403 на базе
 * существующего {@link ErrorDto}.
 */
public final class AuthSecurityDefaults {

    public static final String[] PUBLIC_SWAGGER_PATHS = {
        "/favicon.ico",
        "/swagger-ui.html",
        "/swagger-ui/**",
        "/v3/api-docs/**",
        "/swagger-resources/**"
    };

    public static final String[] PUBLIC_ACTUATOR_PATHS = {"/actuator/**"};

    public static final String WWW_AUTHENTICATE_VALUE = "Bearer realm=\"api\"";

    private AuthSecurityDefaults() {
    }

    /**
     * Единая точка входа 401 (не аутентифицирован): JSON {@link ErrorDto} + заголовок
     * {@code WWW-Authenticate}.
     */
    public static AuthenticationEntryPoint authenticationEntryPoint(@NonNull ObjectMapper objectMapper) {
        return (request, response, authException) ->
                writeError(response, objectMapper, HttpStatus.UNAUTHORIZED, "Authentication required");
    }

    /**
     * Единый обработчик 403 (доступ запрещён): JSON {@link ErrorDto}.
     */
    public static AccessDeniedHandler accessDeniedHandler(@NonNull ObjectMapper objectMapper) {
        return (request, response, accessDeniedException) ->
                writeError(response, objectMapper, HttpStatus.FORBIDDEN, "Access denied");
    }

    public static void writeError(@NonNull HttpServletResponse response, @NonNull ObjectMapper objectMapper,
            @NonNull HttpStatus status, @NonNull String message) throws IOException {
        ErrorDto errorDto = ErrorDto.builder()
                .message(message)
                .status(status.value())
                .timestamp(LocalDateTime.now())
                .build();
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        if (status == HttpStatus.UNAUTHORIZED) {
            response.setHeader("WWW-Authenticate", WWW_AUTHENTICATE_VALUE);
        }
        response.getWriter().write(objectMapper.writeValueAsString(errorDto));
    }
}
