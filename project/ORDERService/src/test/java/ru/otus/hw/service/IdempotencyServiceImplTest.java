package ru.otus.hw.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.otus.hw.config.properties.IdempotencyProperties;
import ru.otus.hw.dto.OrderCreateDto;
import ru.otus.hw.dto.OrderResponseDto;
import ru.otus.hw.models.IdempotencyKey;
import ru.otus.hw.models.Order;
import ru.otus.hw.models.OrderSagaState.SagaStatus;
import ru.otus.hw.repository.IdempotencyKeyRepository;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceImplTest {
    private static final UUID KEY = UUID.fromString("3f2b8c1a-9d4e-4a7f-8b2c-6e1d0a9b5c3d");

    @Mock
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Mock
    private IdempotencyProperties idempotencyProperties;

    private IdempotencyServiceImpl idempotencyService;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        idempotencyService = new IdempotencyServiceImpl(idempotencyKeyRepository, objectMapper,
                idempotencyProperties);
    }

    private OrderCreateDto dto(Long userId, BigDecimal price) {
        return new OrderCreateDto(price, "test order", 11L, 3,
                LocalDate.now().plusDays(1), LocalTime.of(10, 0), LocalTime.of(12, 0));
    }

    @Test
    @DisplayName("requestHash: одинаковый payload -> одинаковый SHA-256 (64 hex-символа)")
    void shouldProduceStableHashForSamePayload() {
        String hash1 = idempotencyService.requestHash(dto(7L, new BigDecimal("250.00")));
        String hash2 = idempotencyService.requestHash(dto(7L, new BigDecimal("250.00")));

        assertThat(hash1).isEqualTo(hash2).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("requestHash: разный payload (цена/товар) -> разные хеши. "
            + "userId не входит в hash - владелец сверяется отдельно по JWT")
    void shouldProduceDifferentHashForDifferentPayload() {
        String base = idempotencyService.requestHash(dto(7L, new BigDecimal("250.00")));
        String otherPrice = idempotencyService.requestHash(dto(7L, new BigDecimal("251.00")));

        assertThat(base).isNotEqualTo(otherPrice);
        assertThat(idempotencyService.requestHash(dto(8L, new BigDecimal("250.00")))).isEqualTo(base);
    }

    @Test
    @DisplayName("newEntry: ключ, владелец, hash, заказ, STARTED и expires_at = now + TTL")
    void shouldBuildEntryWithTtl() {
        when(idempotencyProperties.getTtl()).thenReturn(Duration.ofHours(24));

        IdempotencyKey entry = idempotencyService.newEntry(KEY, 7L, "hash", 100L);

        assertThat(entry.getIdempotencyKey()).isEqualTo(KEY.toString());
        assertThat(entry.getUserId()).isEqualTo(7L);
        assertThat(entry.getRequestHash()).isEqualTo("hash");
        assertThat(entry.getOrderId()).isEqualTo(100L);
        assertThat(entry.getSagaStatus()).isEqualTo(SagaStatus.STARTED);
        assertThat(entry.getResponseStatus()).isNull();
        assertThat(entry.getExpiresAt())
                .isBetween(LocalDateTime.now().plusHours(23), LocalDateTime.now().plusHours(25));
    }

    @Test
    @DisplayName("recordSuccess: сохраняет 201 + JSON заказа + CONFIRMED")
    void shouldStoreSuccessfulResponse() {
        IdempotencyKey entry = IdempotencyKey.builder()
                .idempotencyKey(KEY.toString())
                .userId(7L)
                .requestHash("hash")
                .orderId(100L)
                .sagaStatus(SagaStatus.STARTED)
                .build();
        when(idempotencyKeyRepository.findByIdempotencyKey(KEY.toString())).thenReturn(Optional.of(entry));
        when(idempotencyKeyRepository.save(any(IdempotencyKey.class))).thenAnswer(inv -> inv.getArgument(0));
        OrderResponseDto response = new OrderResponseDto(100L, 7L, new BigDecimal("250.00"), "test order",
                11L, 3, LocalDate.now().plusDays(1), LocalTime.of(10, 0), LocalTime.of(12, 0),
                Order.OrderStatus.PLACED);

        idempotencyService.recordSuccess(KEY, response);

        ArgumentCaptor<IdempotencyKey> captor = ArgumentCaptor.forClass(IdempotencyKey.class);
        verify(idempotencyKeyRepository).save(captor.capture());
        IdempotencyKey saved = captor.getValue();
        assertThat(saved.getResponseStatus()).isEqualTo(201);
        assertThat(saved.getSagaStatus()).isEqualTo(SagaStatus.CONFIRMED);
        assertThat(saved.getResponseBody()).contains("\"id\":100");
        assertThat(idempotencyService.readStoredResponse(saved)).isEqualTo(response);
    }

    @Test
    @DisplayName("recordSagaStatus: обновляет saga_status провалившейся саги, ответ не сохраняет")
    void shouldRecordSagaStatusOnFailure() {
        IdempotencyKey entry = IdempotencyKey.builder()
                .idempotencyKey(KEY.toString())
                .userId(7L)
                .requestHash("hash")
                .orderId(100L)
                .sagaStatus(SagaStatus.STARTED)
                .build();
        when(idempotencyKeyRepository.findByIdempotencyKey(KEY.toString())).thenReturn(Optional.of(entry));
        when(idempotencyKeyRepository.save(any(IdempotencyKey.class))).thenAnswer(inv -> inv.getArgument(0));

        idempotencyService.recordSagaStatus(KEY, SagaStatus.COMPENSATED);

        ArgumentCaptor<IdempotencyKey> captor = ArgumentCaptor.forClass(IdempotencyKey.class);
        verify(idempotencyKeyRepository).save(captor.capture());
        assertThat(captor.getValue().getSagaStatus()).isEqualTo(SagaStatus.COMPENSATED);
        assertThat(captor.getValue().getResponseStatus()).isNull();
    }

    @Test
    @DisplayName("recordSagaStatus: ключ не найден - no-op без ошибок")
    void shouldDoNothingWhenKeyMissingOnRecord() {
        when(idempotencyKeyRepository.findByIdempotencyKey(KEY.toString())).thenReturn(Optional.empty());

        idempotencyService.recordSagaStatus(KEY, SagaStatus.COMPENSATION_FAILED);

        verify(idempotencyKeyRepository, never()).save(any());
    }

    @Test
    @DisplayName("cleanupExpiredKeys: удаляет ключи с истёкшим expires_at")
    void shouldDeleteExpiredKeys() {
        when(idempotencyKeyRepository.deleteByExpiresAtBefore(any(LocalDateTime.class))).thenReturn(3L);

        idempotencyService.cleanupExpiredKeys();

        verify(idempotencyKeyRepository).deleteByExpiresAtBefore(any(LocalDateTime.class));
    }
}
