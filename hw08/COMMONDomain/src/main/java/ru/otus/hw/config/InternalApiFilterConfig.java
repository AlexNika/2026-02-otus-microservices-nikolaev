package ru.otus.hw.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import ru.otus.hw.config.properties.InternalApiKeyConfig;
import ru.otus.hw.dto.ErrorDto;
import ru.otus.hw.exception.InternalApiKeyException;

import java.io.IOException;
import java.time.LocalDateTime;

/**
 * Common filter protecting {@code /internal/**} endpoints of all microservices
 * with the {@code X-Internal-API-Key} header check.
 * <p>
 * The {@link InternalApiKeyConfig} bean is resolved lazily via {@link ObjectProvider}
 * so services without internal endpoints (and without the configured key) still start up;
 * any request to {@code /internal/**} in that case fails closed with 500.
 */
@Slf4j
@Component
public class InternalApiFilterConfig extends OncePerRequestFilter {

    private static final String INTERNAL_API_KEY_HEADER = "X-Internal-API-Key";

    private final ObjectProvider<InternalApiKeyConfig> internalApiKeyConfigProvider;

    private final HandlerExceptionResolver handlerExceptionResolver;

    public InternalApiFilterConfig(ObjectProvider<InternalApiKeyConfig> internalApiKeyConfigProvider,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver handlerExceptionResolver) {
        this.internalApiKeyConfigProvider = internalApiKeyConfigProvider;
        this.handlerExceptionResolver = handlerExceptionResolver;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {

        String path = request.getRequestURI();
        log.debug("InternalApiFilter - filtering path: {}", path);
        if (!path.startsWith("/internal/")) {
            log.debug("Skip doFilterInternal API processing");
            filterChain.doFilter(request, response);
            return;
        }

        String validApiKey = getConfiguredApiKey();
        String providedApiKey = request.getHeader(INTERNAL_API_KEY_HEADER);
        log.debug("Checking API key for internal endpoint: {}", path);

        if (validApiKey == null || validApiKey.isBlank()) {
            log.error("INTERNAL_API_KEY is not configured! Check application.yaml/.env");
            sendApiKeyNotConfiguredResponse(response);
            return;
        }

        try {
            validateApiKey(providedApiKey, validApiKey, path);
        } catch (InternalApiKeyException ex) {
            log.warn("Unauthorized internal request to {} — {}", path, ex.getMessage());
            handlerExceptionResolver.resolveException(request, response, null, ex);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private @Nullable String getConfiguredApiKey() {
        InternalApiKeyConfig config = internalApiKeyConfigProvider.getIfAvailable();
        return config != null ? config.getInternalApiKey() : null;
    }

    private void validateApiKey(String providedApiKey, String validApiKey, String path) {
        if (providedApiKey == null || providedApiKey.isBlank()) {
            throw new InternalApiKeyException(
                    "Missing required header 'X-Internal-API-Key' for internal endpoint " + path);
        }
        if (!validApiKey.equals(providedApiKey)) {
            throw new InternalApiKeyException("Invalid internal API key for internal endpoint " + path);
        }
    }

    private void sendApiKeyNotConfiguredResponse(@NonNull HttpServletResponse response) throws IOException {
        ErrorDto errorDto = ErrorDto.builder()
                .message("Internal API key is not configured")
                .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                .timestamp(LocalDateTime.now())
                .build();

        response.setStatus(errorDto.status());
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        String errorJson = String.format(
                "{\"message\":\"%s\",\"status\":%d,\"timestamp\":\"%s\"}",
                escapeJson(errorDto.message()),
                errorDto.status(),
                errorDto.timestamp().toString()
        );
        response.getWriter().write(errorJson);
    }

    private @NonNull String escapeJson(String input) {
        return input == null ? "" : input.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
