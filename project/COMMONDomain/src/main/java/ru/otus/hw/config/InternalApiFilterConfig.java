package ru.otus.hw.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import ru.otus.hw.config.properties.InternalApiKeyConfig;
import ru.otus.hw.exception.InternalApiKeyException;
import ru.otus.hw.security.InternalApiKeySupport;

import java.io.IOException;

/**
 * Common filter protecting {@code /internal/**} endpoints of all microservices
 * with the {@code X-Internal-API-Key} header check.
 * <p>
 * The {@link InternalApiKeyConfig} bean is resolved lazily via {@link ObjectProvider}
 * so services without internal endpoints (and without the configured key) still start up;
 * any request to {@code /internal/**} in that case fails closed with 500.
 * <p>
 * Логика сравнения ключа переиспользуется из {@link InternalApiKeySupport}.
 *
 * <p><b>Отключён как standalone servlet-фильтр</b> (нет {@code @Component}): все сервисы
 * переведены на Security-цепочку №0 с ключ-фильтром
 * {@link ru.otus.hw.security.InternalApiKeyAuthFilter} - так нет двойной проверки
 * {@code /internal/**} и защита не зависит от порядка servlet-фильтров.
 */
@Slf4j
public class InternalApiFilterConfig extends OncePerRequestFilter {

    private final ObjectProvider<InternalApiKeyConfig> internalApiKeyConfigProvider;

    private final HandlerExceptionResolver handlerExceptionResolver;

    private final ObjectMapper objectMapper;

    public InternalApiFilterConfig(ObjectProvider<InternalApiKeyConfig> internalApiKeyConfigProvider,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver handlerExceptionResolver,
            ObjectMapper objectMapper) {
        this.internalApiKeyConfigProvider = internalApiKeyConfigProvider;
        this.handlerExceptionResolver = handlerExceptionResolver;
        this.objectMapper = objectMapper;
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

        String configuredApiKey = InternalApiKeySupport.resolveConfiguredKey(internalApiKeyConfigProvider);
        String providedApiKey = request.getHeader(InternalApiKeySupport.INTERNAL_API_KEY_HEADER);
        log.debug("Checking API key for internal endpoint: {}", path);

        if (!InternalApiKeySupport.isConfigured(configuredApiKey)) {
            log.error("INTERNAL_API_KEY is not configured! Check application.yaml/.env");
            InternalApiKeySupport.writeKeyNotConfigured(response, objectMapper);
            return;
        }

        try {
            InternalApiKeySupport.validateKey(providedApiKey, configuredApiKey, path);
        } catch (InternalApiKeyException ex) {
            log.warn("Unauthorized internal request to {} - {}", path, ex.getMessage());
            handlerExceptionResolver.resolveException(request, response, null, ex);
            return;
        }

        filterChain.doFilter(request, response);
    }
}
