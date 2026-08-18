package ru.otus.hw.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.otus.hw.config.properties.IdempotencyConfig;
import ru.otus.hw.dto.OrderCreateDto;
import ru.otus.hw.dto.OrderResponseDto;
import ru.otus.hw.models.IdempotencyKey;
import ru.otus.hw.models.OrderSagaState.SagaStatus;
import ru.otus.hw.repository.IdempotencyKeyRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyServiceImpl implements IdempotencyService {

    /**
     * Отдельный mapper для хеширования: порядок полей record-класса детерминирован,
     * даты - в ISO-формате, без зависимости от MVC-конфигурации.
     */
    private static final ObjectMapper HASH_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final IdempotencyKeyRepository idempotencyKeyRepository;

    private final ObjectMapper objectMapper;

    private final IdempotencyConfig idempotencyConfig;

    @Override
    @Transactional(readOnly = true)
    public Optional<IdempotencyKey> find(@NonNull UUID idempotencyKey) {
        return idempotencyKeyRepository.findByIdempotencyKey(idempotencyKey.toString());
    }

    @Override
    public String requestHash(@NonNull OrderCreateDto orderCreateDto) {
        try {
            String payload = HASH_MAPPER.writeValueAsString(orderCreateDto);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize order payload for request hash", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm is not available", e);
        }
    }

    @Override
    public IdempotencyKey newEntry(@NonNull UUID idempotencyKey, @NonNull Long userId,
                                   @NonNull String requestHash, Long orderId) {
        return IdempotencyKey.builder()
                .idempotencyKey(idempotencyKey.toString())
                .userId(userId)
                .requestHash(requestHash)
                .orderId(orderId)
                .sagaStatus(SagaStatus.STARTED)
                .expiresAt(LocalDateTime.now().plus(idempotencyConfig.getTtl()))
                .build();
    }

    @Override
    @Transactional
    public void recordSuccess(@NonNull UUID idempotencyKey, @NonNull OrderResponseDto response) {
        idempotencyKeyRepository.findByIdempotencyKey(idempotencyKey.toString())
                .ifPresent(entry -> {
                    entry.setResponseStatus(HttpStatus.CREATED.value());
                    entry.setResponseBody(writeJson(response));
                    entry.setSagaStatus(SagaStatus.CONFIRMED);
                    idempotencyKeyRepository.save(entry);
                    log.info("Idempotent response stored for key: {}, orderId: {}", idempotencyKey, response.id());
                });
    }

    @Override
    @Transactional
    public void recordSagaStatus(@NonNull UUID idempotencyKey, @NonNull SagaStatus sagaStatus) {
        idempotencyKeyRepository.findByIdempotencyKey(idempotencyKey.toString())
                .ifPresent(entry -> {
                    entry.setSagaStatus(sagaStatus);
                    idempotencyKeyRepository.save(entry);
                    log.info("Idempotency key: {} updated with saga status: {}", idempotencyKey, sagaStatus);
                });
    }

    @Override
    public OrderResponseDto readStoredResponse(@NonNull IdempotencyKey entry) {
        try {
            return objectMapper.readValue(entry.getResponseBody(), OrderResponseDto.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Corrupted stored idempotent response for key: " + entry.getIdempotencyKey(), e);
        }
    }

    @Override
    @Scheduled(fixedDelayString = "${app.idempotency.cleanup-interval:PT1H}")
    @Transactional
    public void cleanupExpiredKeys() {
        long deleted = idempotencyKeyRepository.deleteByExpiresAtBefore(LocalDateTime.now());
        if (deleted > 0) {
            log.info("Removed {} expired idempotency keys", deleted);
        } else {
            log.debug("No expired idempotency keys to remove");
        }
    }

    private @NonNull String writeJson(@NonNull OrderResponseDto response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize order response for idempotent storage", e);
        }
    }
}
