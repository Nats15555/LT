package com.loadtest.app.service;

import com.loadtest.app.persistence.LoadTestSystemSettingsEntity;
import com.loadtest.app.persistence.LoadTstSystemSettingsRepository;
import com.loadtest.app.persistence.TestTaskKafkaPendingEntity;
import com.loadtest.app.persistence.TestTaskKafkaPendingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
    private TestTaskKafkaSlotDispatchService testTaskKafkaSlotDispatchService;

    private QueuePauseService service;

    @BeforeEach
    void setUp() {
        service = new QueuePauseService(settingsRepository, pendingRepository, testTaskKafkaSlotDispatchService);
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
    void setPaused_whenResuming_triggersSlotDispatch() {
        when(settingsRepository.findById(1)).thenReturn(Optional.of(
                LoadTestSystemSettingsEntity.builder().id(1).queuePaused(true).updatedAt(OffsetDateTime.now()).build()));
        when(pendingRepository.count()).thenReturn(2L);
        QueuePauseService.QueuePauseState st = service.setPaused(false);
        verify(testTaskKafkaSlotDispatchService).dispatchAvailableSlots();
        assertThat(st.paused()).isFalse();
    }

    @Test
    void setPaused_trueDoesNotDispatch() {
        when(settingsRepository.findById(1)).thenReturn(Optional.of(
                LoadTestSystemSettingsEntity.builder().id(1).queuePaused(false).updatedAt(OffsetDateTime.now()).build()));
        when(pendingRepository.count()).thenReturn(0L);
        QueuePauseService.QueuePauseState st = service.setPaused(true);
        assertThat(st.paused()).isTrue();
        verify(testTaskKafkaSlotDispatchService, never()).dispatchAvailableSlots();
    }

    @Test
    void cancelPendingKafkaDispatch_deletesRow() {
        UUID taskId = UUID.randomUUID();
        service.cancelPendingKafkaDispatch(taskId);
        verify(pendingRepository).deleteById(taskId);
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
