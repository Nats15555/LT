package com.loadtest.app.service;

import com.loadtest.app.dto.TestTaskEvent;
import com.loadtest.app.persistence.LoadTestSystemSettingsEntity;
import com.loadtest.app.persistence.LoadTstSystemSettingsRepository;
import com.loadtest.app.persistence.TestTaskKafkaPendingEntity;
import com.loadtest.app.persistence.TestTaskKafkaPendingRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueuePauseService {

    private static final int SETTINGS_ID = 1;

    private final LoadTstSystemSettingsRepository settingsRepository;
    private final TestTaskKafkaPendingRepository pendingRepository;
    private final KafkaOutboxService kafkaOutboxService;

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

    public void recordPendingKafkaDispatch(UUID taskId) {
        if (!pendingRepository.existsById(taskId)) {
            pendingRepository.save(TestTaskKafkaPendingEntity.builder()
                    .taskId(taskId)
                    .createdAt(OffsetDateTime.now())
                    .build());
        }
        log.info("Task {} held for Kafka until queue pause is released (test_task_kafka_pending)", taskId);
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
            drainPendingTestTaskKafkaEvents();
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

    private void drainPendingTestTaskKafkaEvents() {
        while (true) {
            List<TestTaskKafkaPendingEntity> batch =
                    pendingRepository.findByOrderByCreatedAtAsc(PageRequest.of(0, 100));
            if (batch.isEmpty()) {
                break;
            }
            for (TestTaskKafkaPendingEntity row : batch) {
                String taskId = row.getTaskId().toString();
                try {
                    kafkaOutboxService.sendTestTaskEvent(taskId, new TestTaskEvent(taskId));
                    pendingRepository.deleteById(row.getTaskId());
                    log.info("Released held Kafka event for task {} after queue unpause", taskId);
                } catch (RuntimeException e) {
                    log.error("Failed to dispatch held Kafka event for task {}, will retry on next unpause", taskId, e);
                    return;
                }
            }
        }
    }

    public record QueuePauseState(boolean paused, long pendingKafkaDispatchCount) {}
}
