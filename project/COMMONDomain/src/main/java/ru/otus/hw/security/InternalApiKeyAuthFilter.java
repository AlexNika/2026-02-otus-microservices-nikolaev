package ru.otus.hw.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.otus.hw.config.properties.InternalApiKeyConfig;
import ru.otus.hw.exception.InternalApiKeyException;

import java.io.IOException;

/**
 * Ключ-фильтр для Security-цепочки №0 ({@code securityMatcher("/internal/**")}) - адаптер
 * существующей логики {@code InternalApiFilterConfig} в виде фильтра, пригодного для
 * {@code addFilterBefore(..., AuthorizationFilter.class)} внутрь цепочки.
 *
 * <p>Подключение именно в отдельную цепочку устраняет зависимость от порядка servlet-фильтров:
 * любой запрос на {@code /internal/**} без валидного {@code X-Internal-API-Key} получает 401
 * ещё внутри цепочки, даже если порядок фильтров собьётся. Ошибка пишется в ответ напрямую
 * (внутри цепочки {@code @RestControllerAdvice} не сработает).
 *
 * <p>Не является Spring-компонентом: экземпляр создаётся в SecurityConfig каждого сервиса
 * и встраивается в цепочку №0. Логика сравнения ключа переиспользуется из
 * {@link InternalApiKeySupport}.
 */
@Slf4j
public class InternalApiKeyAuthFilter extends OncePerRequestFilter {

    private final ObjectProvider<InternalApiKeyConfig> internalApiKeyConfigProvider;

    private final ObjectMapper objectMapper;

    public InternalApiKeyAuthFilter(
            @NonNull ObjectProvider<InternalApiKeyConfig> internalApiKeyConfigProvider,
            @NonNull ObjectMapper objectMapper) {
        this.internalApiKeyConfigProvider = internalApiKeyConfigProvider;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response, @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();
        String configuredKey = InternalApiKeySupport.resolveConfiguredKey(internalApiKeyConfigProvider);

        if (!InternalApiKeySupport.isConfigured(configuredKey)) {
            log.error("INTERNAL_API_KEY is not configured! Check application.yaml/.env (path: {})", path);
            InternalApiKeySupport.writeKeyNotConfigured(response, objectMapper);
            return;
        }

        String providedKey = request.getHeader(InternalApiKeySupport.INTERNAL_API_KEY_HEADER);
        try {
            InternalApiKeySupport.validateKey(providedKey, configuredKey, path);
        } catch (InternalApiKeyException ex) {
            log.warn("Unauthorized internal request to {} - {}", path, ex.getMessage());
            AuthSecurityDefaults.writeError(response, objectMapper, HttpStatus.UNAUTHORIZED, ex.getMessage());
            return;
        }

        filterChain.doFilter(request, response);
    }
}
