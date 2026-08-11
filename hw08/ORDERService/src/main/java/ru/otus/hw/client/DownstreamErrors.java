package ru.otus.hw.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClientResponseException;
import ru.otus.hw.dto.ErrorDto;

/**
 * Утилита разбора ошибок downstream-сервисов, возвращаемых в теле {@link ErrorDto}.
 */
@Slf4j
public final class DownstreamErrors {

    private DownstreamErrors() {
    }

    /**
     * Пытается извлечь машинно-читаемый код ошибки из тела ответа downstream-сервиса.
     * Если тело не является корректным {@link ErrorDto}, возвращает {@code null}.
     */
    public static String extractCode(RestClientResponseException e, ObjectMapper objectMapper) {
        try {
            ErrorDto errorDto = objectMapper.readValue(e.getResponseBodyAsString(), ErrorDto.class);
            return errorDto != null ? errorDto.code() : null;
        } catch (Exception ex) {
            log.warn("Could not parse downstream ErrorDto body: {}", e.getResponseBodyAsString());
            return null;
        }
    }

    public static String describe(RestClientResponseException e, String serviceName) {
        return String.format("%s returned error: status=%d, response=%s",
                serviceName, e.getStatusCode().value(), e.getResponseBodyAsString());
    }
}
