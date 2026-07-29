package ru.otus.hw.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.otus.hw.config.properties.InternalApiKeyConfig;
import ru.otus.hw.dto.ErrorDto;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class InternalApiFilterConfig extends OncePerRequestFilter {

    private final InternalApiKeyConfig internalApiKeyConfig;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {

        String validApiKey = internalApiKeyConfig.getInternalApiKey();
        String path = request.getRequestURI();
        log.debug("InternalApiFilter - filtering path: {}", path);
        if (!path.startsWith("/internal/")) {
            log.debug("Skip doFilterInternal API processing");
            filterChain.doFilter(request, response);
            return;
        }
        String providedApiKey = request.getHeader("X-Internal-API-Key");
        log.debug("Checking API key for internal endpoint: {}", path);
        log.debug("Received API key: {}, Expected: {}", providedApiKey, validApiKey);

        if (validApiKey == null || validApiKey.isBlank()) {
            log.error("INTERNAL_API_KEY is not configured in BILLINGService! Check application.yaml/.env");
            sendErrorResponse(response, HttpStatus.INTERNAL_SERVER_ERROR, "Internal API key is not configured");
            return;
        }

        if (providedApiKey.isBlank() || !validApiKey.equals(providedApiKey)) {
            log.warn("Unauthorized internal request to {} — invalid or missing X-Internal-API-Key", path);
            sendErrorResponse(response, HttpStatus.FORBIDDEN, "Invalid or missing internal API key");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void sendErrorResponse(@NonNull HttpServletResponse response,
                                   @NonNull HttpStatus status,
                                   String message) throws IOException {
        ErrorDto errorDto = ErrorDto.builder()
                .message(message)
                .status(status.value())
                .timestamp(java.time.LocalDateTime.now())
                .build();

        response.setStatus(status.value());
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