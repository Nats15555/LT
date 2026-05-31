package com.loadtest.app.service;

import com.loadtest.app.dto.TestTaskEvent;
import com.loadtest.app.persistence.LoadTestSystemSettingsEntity;
import com.loadtest.app.persistence.LoadTstSystemSettingsRepository;
import com.loadtest.app.persistence.TestTaskKafkaPendingEntity;
import com.loadtest.app.persistence.TestTaskKafkaPendingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueuePauseServiceTest {

    @Mock
    private LoadTstSystemSettingsRepository settingsRepository;
    @Mock
    private TestTaskKafkaPendingRepository pendingRepository;
    @Mock
    private KafkaOutboxService kafkaOutboxService;

    private QueuePauseService service;

    @BeforeEach
    void setUp() {
        service = new QueuePauseService(settingsRepository, pendingRepository, kafkaOutboxService);
    }

    @Test
    void ensureSchema_runsDdl() {
        service.ensureSchema();
        verify(settingsRepository).ensureTable();
        verify(settingsRepository).ensureDefaultRow();
        verify(pendingRepository).ensureTable();
        verify(pendingRepository).ensureIndex();
    }

    @Test
    void isQueuePaused_readsFlag() {
        when(settingsRepository.findById(1)).thenReturn(Optional.of(
                LoadTestSystemSettingsEntity.builder().id(1).queuePaused(true).updatedAt(OffsetDateTime.now()).build()));
        assertThat(service.isQueuePaused()).isTrue();
    }

    @Test
    void isQueuePaused_onErrorReturnsFalse() {
        when(settingsRepository.findById(1)).thenThrow(new RuntimeException("db"));
        assertThat(service.isQueuePaused()).isFalse();
    }

    @Test
    void countPendingKafkaDispatches_handlesError() {
        when(pendingRepository.count()).thenThrow(new RuntimeException());
        assertThat(service.countPendingKafkaDispatches()).isZero();
    }

    @Test
    void countPendingKafkaDispatches_returnsCount() {
        when(pendingRepository.count()).thenReturn(5L);
        assertThat(service.countPendingKafkaDispatches()).isEqualTo(5L);
    }

    @Test
    void recordPendingKafkaDispatch_inserts() {
        UUID id = UUID.randomUUID();
        when(pendingRepository.existsById(id)).thenReturn(false);
        service.recordPendingKafkaDispatch(id);
        verify(pendingRepository).save(any(TestTaskKafkaPendingEntity.class));
    }

    @Test
    void setPaused_whenResuming_drainsPending() {
        UUID taskId = UUID.randomUUID();
        when(settingsRepository.findById(1)).thenReturn(Optional.of(
                LoadTestSystemSettingsEntity.builder().id(1).queuePaused(true).updatedAt(OffsetDateTime.now()).build()));
        when(pendingRepository.findByOrderByCreatedAtAsc(any(Pageable.class)))
                .thenReturn(List.of(TestTaskKafkaPendingEntity.builder()
                        .taskId(taskId)
                        .createdAt(OffsetDateTime.now())
                        .build()))
                .thenReturn(List.of());
        QueuePauseService.QueuePauseState st = service.setPaused(false);
        verify(kafkaOutboxService).sendTestTaskEvent(eq(taskId.toString()), any(TestTaskEvent.class));
        verify(pendingRepository).deleteById(taskId);
        assertThat(st).isNotNull();
    }

    @Test
    void setPaused_trueDoesNotDrain() {
        when(settingsRepository.findById(1)).thenReturn(Optional.of(
                LoadTestSystemSettingsEntity.builder().id(1).queuePaused(false).updatedAt(OffsetDateTime.now()).build()));
        when(pendingRepository.count()).thenReturn(0L);
        QueuePauseService.QueuePauseState st = service.setPaused(true);
        assertThat(st.paused()).isTrue();
        verify(pendingRepository, never()).findByOrderByCreatedAtAsc(any(Pageable.class));
    }

    @Test
    void setPaused_drainStopsWhenKafkaFails() {
        UUID taskId = UUID.randomUUID();
        when(settingsRepository.findById(1)).thenReturn(Optional.of(
                LoadTestSystemSettingsEntity.builder().id(1).queuePaused(true).updatedAt(OffsetDateTime.now()).build()));
        when(pendingRepository.findByOrderByCreatedAtAsc(any(Pageable.class)))
                .thenReturn(List.of(TestTaskKafkaPendingEntity.builder()
                        .taskId(taskId)
                        .createdAt(OffsetDateTime.now())
                        .build()));
        doThrow(new RuntimeException("send failed")).when(kafkaOutboxService)
                .sendTestTaskEvent(anyString(), any(TestTaskEvent.class));
        service.setPaused(false);
        verify(pendingRepository, never()).deleteById(taskId);
    }

    @Test
    void getState_reflectsRepositories() {
        when(settingsRepository.findById(1)).thenReturn(Optional.of(
                LoadTestSystemSettingsEntity.builder().id(1).queuePaused(true).updatedAt(OffsetDateTime.now()).build()));
        when(pendingRepository.count()).thenReturn(3L);
        QueuePauseService.QueuePauseState st = service.getState();
        assertThat(st.paused()).isTrue();
        assertThat(st.pendingKafkaDispatchCount()).isEqualTo(3L);
    }
}
