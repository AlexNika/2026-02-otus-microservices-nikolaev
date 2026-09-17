package ru.otus.hw.security;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import ru.otus.hw.config.properties.InternalApiKeyConfig;
import ru.otus.hw.exception.InternalApiKeyException;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Общая логика защиты контура {@code /internal/**} статическим ключом
 * {@code X-Internal-API-Key}, переиспользуемая обоими механизмами:
 * standalone servlet-фильтром (исторический {@code InternalApiFilterConfig}) и
 * ключ-фильтром внутри Security-цепочки ({@link InternalApiKeyAuthFilter}).
 *
 * <p>Сравнение ключа и сообщения об ошибках сосредоточены здесь в одном месте,
 * чтобы два механизма не могли разойтись в поведении.
 */
public final class InternalApiKeySupport {

    public static final String INTERNAL_API_KEY_HEADER = "X-Internal-API-Key";

    private InternalApiKeySupport() {
    }

    /**
     * Возвращает настроенный ключ или {@code null}, если бин конфигурации отсутствует
     * / ключ не задан (fail-closed: запросы на {@code /internal/**} должны получить 500).
     */
    public static @Nullable String resolveConfiguredKey(
            @NonNull ObjectProvider<InternalApiKeyConfig> provider) {
        InternalApiKeyConfig config = provider.getIfAvailable();
        return config != null ? config.getInternalApiKey() : null;
    }

    public static boolean isConfigured(@Nullable String configuredKey) {
        return configuredKey != null && !configuredKey.isBlank();
    }

    /**
     * Проверяет предоставленный ключ; бросает {@link InternalApiKeyException} при отсутствии
     * или несовпадении. Вызывающий код решает, как именно вернуть ошибку клиенту.
     */
    public static void validateKey(@Nullable String providedApiKey, @NonNull String configuredKey,
            @NonNull String path) {
        if (providedApiKey == null || providedApiKey.isBlank()) {
            throw new InternalApiKeyException(
                    "Missing required header '" + INTERNAL_API_KEY_HEADER + "' for internal endpoint " + path);
        }
        if (!configuredKey.equals(providedApiKey)) {
            throw new InternalApiKeyException("Invalid internal API key for internal endpoint " + path);
        }
    }

    /**
     * Пишет 500 (fail-closed) в случае, когда ключ не настроен вовсе.
     */
    public static void writeKeyNotConfigured(@NonNull HttpServletResponse response,
            @NonNull ObjectMapper objectMapper) throws IOException {
        AuthSecurityDefaults.writeError(response, objectMapper, HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal API key is not configured");
    }
}
