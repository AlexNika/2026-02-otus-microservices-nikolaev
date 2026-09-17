package ru.otus.hw.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.otus.hw.config.properties.OutboxProperties;
import ru.otus.hw.models.OutboxEvent.OutboxStatus;
import ru.otus.hw.repository.OutboxEventRepository;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Scheduled-очистка user_outbox: удаляются толькоSENT-события,
 * старше периода хранения; на удалении 0 строк - только лог без побочных действий.
 */
@ExtendWith(MockitoExtension.class)
class OutboxEventCleanupServiceTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private OutboxProperties outboxProperties;

    private OutboxEventCleanupService cleanupService;

    private static final Duration RETENTION = Duration.ofDays(7);

    @BeforeEach
    void setUp() {
        cleanupService = new OutboxEventCleanupService(outboxEventRepository, outboxProperties);
    }

    @Test
    @DisplayName("удаление только по статусу SENT и cutoff = now - retention")
    void shouldDeleteOnlySentEventsOlderThanRetention() {
        when(outboxProperties.getRetention()).thenReturn(RETENTION);
        when(outboxEventRepository.deleteByStatusAndSentAtBefore(
                org.mockito.ArgumentMatchers.eq(OutboxStatus.SENT),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class)))
                .thenReturn(4L);

        cleanupService.cleanupSentEvents();

        ArgumentCaptor<OutboxStatus> statusCaptor = ArgumentCaptor.forClass(OutboxStatus.class);
        ArgumentCaptor<LocalDateTime> cutoffCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(outboxEventRepository).deleteByStatusAndSentAtBefore(statusCaptor.capture(), cutoffCaptor.capture());

        assertThat(statusCaptor.getValue()).isEqualTo(OutboxStatus.SENT);
        assertThat(cutoffCaptor.getValue())
                .isBetween(LocalDateTime.now().minus(RETENTION).minusMinutes(1),
                        LocalDateTime.now().minus(RETENTION).plusMinutes(1));
        verifyNoMoreInteractions(outboxEventRepository);
    }

    @Test
    @DisplayName("нет просроченных SENT-событий - удаление не выполняется повторно")
    void shouldDoNothingWhenThereAreNoExpiredSentEvents() {
        when(outboxProperties.getRetention()).thenReturn(RETENTION);
        when(outboxEventRepository.deleteByStatusAndSentAtBefore(
                org.mockito.ArgumentMatchers.eq(OutboxStatus.SENT),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class)))
                .thenReturn(0L);

        cleanupService.cleanupSentEvents();

        verify(outboxEventRepository).deleteByStatusAndSentAtBefore(
                org.mockito.ArgumentMatchers.eq(OutboxStatus.SENT),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class));
        verifyNoMoreInteractions(outboxEventRepository);
    }

    @Test
    @DisplayName("NEW и FAILED в вызов удаления не попадают")
    void shouldNeverTouchNewOrFailedEvents() {
        when(outboxProperties.getRetention()).thenReturn(RETENTION);
        when(outboxEventRepository.deleteByStatusAndSentAtBefore(
                org.mockito.ArgumentMatchers.eq(OutboxStatus.SENT),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class)))
                .thenReturn(2L);

        cleanupService.cleanupSentEvents();

        ArgumentCaptor<OutboxStatus> statusCaptor = ArgumentCaptor.forClass(OutboxStatus.class);
        verify(outboxEventRepository).deleteByStatusAndSentAtBefore(statusCaptor.capture(),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class));
        assertThat(statusCaptor.getValue()).isNotIn(OutboxStatus.NEW, OutboxStatus.FAILED);
        verifyNoMoreInteractions(outboxEventRepository);
    }
}
