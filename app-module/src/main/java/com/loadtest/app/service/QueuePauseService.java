package com.loadtest.app.service;

import com.loadtest.app.persistence.LoadTestSystemSettingsEntity;
import com.loadtest.app.persistence.LoadTstSystemSettingsRepository;
import com.loadtest.app.persistence.TestTaskKafkaPendingEntity;
import com.loadtest.app.persistence.TestTaskKafkaPendingRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

@Slf4j
@Service
public class QueuePauseService {

    private static final int SETTINGS_ID = 1;

    private final LoadTstSystemSettingsRepository settingsRepository;
    private final TestTaskKafkaPendingRepository pendingRepository;
    private final TestTaskKafkaSlotDispatchService testTaskKafkaSlotDispatchService;

    public QueuePauseService(LoadTstSystemSettingsRepository settingsRepository,
                             TestTaskKafkaPendingRepository pendingRepository,
                             @Lazy TestTaskKafkaSlotDispatchService testTaskKafkaSlotDispatchService) {
        this.settingsRepository = settingsRepository;
        this.pendingRepository = pendingRepository;
        this.testTaskKafkaSlotDispatchService = testTaskKafkaSlotDispatchService;
    }

    @PostConstruct
    public void ensureSchema() {
        settingsRepository.ensureTable();
        settingsRepository.ensureDefaultRow();
        pendingRepository.ensureTable();
        pendingRepository.ensureIndex();
    }

    public boolean isQueuePaused() {
        try {
            return settingsRepository.findById(SETTINGS_ID)
                    .map(LoadTestSystemSettingsEntity::getQueuePaused)
                    .map(Boolean.TRUE::equals)
                    .orElse(false);
        } catch (RuntimeException e) {
            return false;
        }
    }

    public long countPendingKafkaDispatches() {
        try {
            return pendingRepository.count();
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    @Transactional
    public void recordPendingKafkaDispatch(UUID taskId) {
        if (!pendingRepository.existsById(taskId)) {
            pendingRepository.save(TestTaskKafkaPendingEntity.builder()
                    .taskId(taskId)
                    .createdAt(OffsetDateTime.now())
                    .build());
        }
        log.info("Task {} queued for slot-based Kafka dispatch (test_task_kafka_pending)", taskId);
    }

    public void cancelPendingKafkaDispatch(UUID taskId) {
        pendingRepository.deleteById(taskId);
    }

    @Transactional
    public QueuePauseState setPaused(boolean paused) {
        LoadTestSystemSettingsEntity settings = settingsRepository.findById(SETTINGS_ID)
                .orElseGet(this::createDefaultSettings);
        settings.setQueuePaused(paused);
        settings.setUpdatedAt(OffsetDateTime.now());
        settingsRepository.save(settings);
        log.info("Queue pause set to {}", paused);
        if (!paused) {
            testTaskKafkaSlotDispatchService.dispatchAvailableSlots();
        }
        return getState();
    }

    public QueuePauseState getState() {
        return new QueuePauseState(isQueuePaused(), countPendingKafkaDispatches());
    }

    private LoadTestSystemSettingsEntity createDefaultSettings() {
        OffsetDateTime now = OffsetDateTime.now();
        return LoadTestSystemSettingsEntity.builder()
                .id(SETTINGS_ID)
                .queuePaused(false)
                .updatedAt(now)
                .build();
    }

    public record QueuePauseState(boolean paused, long pendingKafkaDispatchCount) {}
}
