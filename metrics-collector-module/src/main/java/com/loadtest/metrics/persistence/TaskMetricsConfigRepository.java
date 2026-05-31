package com.loadtest.metrics.persistence;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@Repository
@RequiredArgsConstructor
public class TaskMetricsConfigRepository {

    private final TestTaskJpaRepository testTaskJpaRepository;
    private final TestTaskHistoryJpaRepository testTaskHistoryJpaRepository;

    public Optional<TaskMetricsConfig> findByTaskId(UUID taskId) {
        Optional<TaskMetricsConfig> fromTask = findFromTestTask(taskId);
        if (fromTask.isPresent()) {
            return fromTask;
        }
        return findFromTestTaskHistory(taskId);
    }

    public Optional<String> findSummarizerNameByTaskId(UUID taskId) {
        try {
            Optional<String> fromTask = testTaskJpaRepository.findById(taskId)
                    .map(TestTaskEntity::getSummarizerName)
                    .filter(name -> !name.isBlank());
            if (fromTask.isPresent()) {
                return fromTask;
            }
            return testTaskHistoryJpaRepository.findById(taskId)
                    .map(TestTaskHistoryEntity::getSummarizerName)
                    .filter(name -> !name.isBlank());
        } catch (RuntimeException e) {
            log.warn("Failed to load summarizer_name for taskId: {}", taskId, e);
            return Optional.empty();
        }
    }

    private Optional<TaskMetricsConfig> findFromTestTask(UUID taskId) {
        try {
            return testTaskJpaRepository.findById(taskId)
                    .map(TestTaskEntity::getMetricsConfig)
                    .map(TaskMetricsConfig::new);
        } catch (RuntimeException e) {
            log.warn("Failed to load metrics config from test_task for taskId: {}", taskId, e);
            return Optional.empty();
        }
    }

    private Optional<TaskMetricsConfig> findFromTestTaskHistory(UUID taskId) {
        try {
            return testTaskHistoryJpaRepository.findById(taskId)
                    .map(TestTaskHistoryEntity::getMetricsConfig)
                    .map(TaskMetricsConfig::new);
        } catch (RuntimeException e) {
            log.warn("Failed to load metrics config from test_task_history for taskId: {}", taskId, e);
            return Optional.empty();
        }
    }

    public record TaskMetricsConfig(String metricsConfigJson) {
    }
}
