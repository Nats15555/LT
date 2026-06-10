package com.loadtest.execution.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadtest.execution.dto.TaskProcessOutcome;
import com.loadtest.execution.dto.TestTaskEvent;
import com.loadtest.execution.dto.TestTaskMessage;
import com.loadtest.execution.persistence.DockerExecutionProfileEntity;
import com.loadtest.execution.persistence.DockerExecutionProfileRepository;
import com.loadtest.execution.persistence.TestTaskEntity;
import com.loadtest.execution.persistence.TestTaskHistoryEntity;
import com.loadtest.execution.persistence.TestTaskHistoryRepository;
import com.loadtest.execution.persistence.TestTaskRepository;
import com.loadtest.execution.persistence.TestTaskStatus;
import com.loadtest.execution.util.TaskLifecycleStatus;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TestTaskExecutionService {

    private static final int DOCKER_SLOT_WAIT_MS = 2000;
    private static final String STATUS_PROCESSING = TaskLifecycleStatus.PROCESSING;
    private static final String STATUS_FAILED = TaskLifecycleStatus.FAILED;
    private static final String STATUS_PENDING = TaskLifecycleStatus.PENDING;

    @Value("${loadtest.summarization.default-summarizer-name:}")
    private String defaultSummarizerName;

    private final TestTaskRepository taskRepository;
    private final TestTaskHistoryRepository historyRepository;
    private final TestTaskProcessor processor;
    private final ObjectMapper objectMapper;
    private final EntityManager entityManager;
    private final DockerExecutionProfileRepository dockerExecutionProfileRepository;

    @Transactional
    public TestTaskRunResult execute(TestTaskEvent event) {
        UUID taskId = UUID.fromString(event.taskId());

        OffsetDateTime now = OffsetDateTime.now();
        boolean claimed;
        try {
            claimed = claimTaskRespectingDockerConcurrency(taskId, now);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for Docker concurrency slot for task {}", taskId);
            return TestTaskRunResult.failed(taskId);
        }

        if (!claimed) {
            TestTaskEntity existing = taskRepository.findById(taskId).orElse(null);
            if (existing != null) {
                log.warn("Task {} already in status {} (claimed or completed), skipping. "
                                + "Expected when the same event is redelivered or processed by another instance.",
                        taskId, existing.getStatus());
            } else {
                log.warn("Task {} not found in database, skipping (row may not be visible yet or already moved to history)",
                        taskId);
            }
            return TestTaskRunResult.duplicate(taskId);
        }

        TestTaskEntity task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalStateException("Task not found after update: " + taskId));

        boolean hasNonEmptyMetricsRequests = hasNonEmptyMetricsRequestsJson(task.getMetricsConfig());
        boolean hasConfiguredSummarizer = hasConfiguredSummarizer(task);

        log.info("Successfully claimed task {} for processing", taskId);

        OffsetDateTime startedAt = OffsetDateTime.now();
        OffsetDateTime finishedAt;
        String finalStatus = STATUS_FAILED;
        String errorMessage = null;
        TestTaskMessage message = null;
        TaskProcessOutcome processOutcome = null;

        ensureHistoryRecordForArtifacts(task, startedAt);

        try {
            message = toMessage(task);
            processOutcome = processor.process(message);
            finalStatus = resolveSuccessHistoryStatus(hasNonEmptyMetricsRequests, hasConfiguredSummarizer);
        } catch (RuntimeException e) {
            finalStatus = STATUS_FAILED;
            errorMessage = e.getMessage();
            log.error("Task {} failed", taskId, e);
        } finally {
            finishedAt = TaskLifecycleStatus.isTerminal(finalStatus) ? OffsetDateTime.now() : null;
            persistTaskOutcomeAndHistory(taskId, finalStatus, errorMessage, finishedAt);
        }

        if (processOutcome != null && !STATUS_FAILED.equals(finalStatus)) {
            return TestTaskRunResult.completed(message, processOutcome, hasNonEmptyMetricsRequests,
                    hasConfiguredSummarizer);
        }
        return TestTaskRunResult.failed(taskId);
    }

    private String resolveSuccessHistoryStatus(boolean hasMetrics, boolean hasSummarizer) {
        if (!hasMetrics && !hasSummarizer) {
            return TaskLifecycleStatus.COMPLETED;
        }
        return TaskLifecycleStatus.PROCESSING;
    }

    private boolean hasConfiguredSummarizer(TestTaskEntity task) {
        if (task.getSummarizerName() != null && !task.getSummarizerName().isBlank()) {
            return true;
        }
        return defaultSummarizerName != null && !defaultSummarizerName.isBlank();
    }

    private void persistTaskOutcomeAndHistory(
            UUID taskId, String finalStatus, String errorMessage, OffsetDateTime finishedAt) {
        try {
            TestTaskEntity taskForUpdate = taskRepository.findById(taskId).orElse(null);
            if (taskForUpdate != null && STATUS_FAILED.equals(finalStatus)) {
                taskForUpdate.setStatus(TestTaskStatus.FAILED);
                taskForUpdate.setErrorMessage(errorMessage);
                taskForUpdate.setUpdatedAt(OffsetDateTime.now());
                taskRepository.save(taskForUpdate);
            }
            updateHistoryAndRemoveTask(taskId, finalStatus, errorMessage, finishedAt);
        } catch (RuntimeException e) {
            log.error("Failed to update task status/history for task {}", taskId, e);
            if (com.loadtest.execution.util.DatabaseAvailabilityService.isDatabaseAccessFailure(e)) {
                throw e;
            }
        }
    }

    private boolean hasNonEmptyMetricsRequestsJson(String metricsConfigJson) {
        if (metricsConfigJson == null || metricsConfigJson.isBlank()) {
            return false;
        }
        try {
            Map<String, Object> root = objectMapper.readValue(metricsConfigJson, new TypeReference<>() {});
            Object requests = root.get("requests");
            return requests instanceof List && !((List<?>) requests).isEmpty();
        } catch (JsonProcessingException e) {
            log.warn("metrics_config is not valid JSON for metrics gate, task will not publish metrics-collection event: {}",
                    e.getMessage());
            return false;
        }
    }

    private TestTaskMessage toMessage(TestTaskEntity task) {
        TestTaskMessage.MetricsConfig metricsConfig = null;
        if (task.getMetricsConfig() != null && !task.getMetricsConfig().trim().isEmpty()) {
            try {
                metricsConfig = objectMapper.readValue(
                        task.getMetricsConfig(),
                        TestTaskMessage.MetricsConfig.class);
            } catch (JsonProcessingException e) {
                log.warn("Failed to parse metricsConfig for task {}", task.getId(), e);
            }
        }

        String profileId = task.getDockerExecutionProfileId() != null
                ? task.getDockerExecutionProfileId().toString()
                : null;

        return new TestTaskMessage(
                task.getId().toString(),
                task.getTestTool(),
                task.getTestFileName(),
                task.getTestFileContentBase64(),
                task.getCommand(),
                task.getExpectedDurationSeconds(),
                task.getStatus().name(),
                null,
                metricsConfig,
                profileId);
    }

    private void ensureHistoryRecordForArtifacts(TestTaskEntity task, OffsetDateTime startedAt) {
        try {
            if (historyRepository.findById(task.getId()).isPresent()) {
                historyRepository.findById(task.getId()).ifPresent(h -> {
                    if (task.getSummarizerName() != null && !task.getSummarizerName().isBlank()
                            && (h.getSummarizerName() == null || h.getSummarizerName().isBlank())) {
                        h.setSummarizerName(task.getSummarizerName());
                        historyRepository.save(h);
                        log.info("Updated history summarizer_name from task for {}", task.getId());
                    }
                });
                return;
            }
            String profileName = dockerExecutionProfileRepository.findById(task.getDockerExecutionProfileId())
                    .map(DockerExecutionProfileEntity::getName)
                    .orElse(null);
            TestTaskHistoryEntity history = TestTaskHistoryEntity.builder()
                    .id(task.getId())
                    .finalStatus(STATUS_PROCESSING)
                    .createdAt(task.getCreatedAt())
                    .testTool(task.getTestTool())
                    .testFileName(task.getTestFileName())
                    .testFileContentBase64(task.getTestFileContentBase64())
                    .command(task.getCommand())
                    .expectedDurationSeconds(task.getExpectedDurationSeconds())
                    .metricsConfig(task.getMetricsConfig())
                    .errorMessage(null)
                    .startedAt(startedAt)
                    .finishedAt(null)
                    .movedAt(OffsetDateTime.now())
                    .summarizerName(task.getSummarizerName())
                    .dockerExecutionProfileId(task.getDockerExecutionProfileId())
                    .dockerProfileName(profileName)
                    .build();
            historyRepository.save(history);
            entityManager.flush();
            log.info("Created history record for task {} (PROCESSING) for artifacts FK", task.getId());
        } catch (RuntimeException e) {
            log.error("Failed to create history record for task {}", task.getId(), e);
            throw new TestTaskHistoryException("Cannot create history record for artifacts", e);
        }
    }

    private void updateHistoryAndRemoveTask(UUID taskId, String finalStatus, String errorMessage,
                                            OffsetDateTime finishedAt) {
        try {
            TestTaskEntity taskRow = taskRepository.findById(taskId).orElse(null);
            historyRepository.findById(taskId).ifPresent(history -> {
                if (taskRow != null && taskRow.getSummarizerName() != null && !taskRow.getSummarizerName().isBlank()
                        && (history.getSummarizerName() == null || history.getSummarizerName().isBlank())) {
                    history.setSummarizerName(taskRow.getSummarizerName());
                }
                history.setFinalStatus(finalStatus);
                history.setErrorMessage(errorMessage);
                history.setFinishedAt(finishedAt);
                history.setMovedAt(OffsetDateTime.now());
                historyRepository.save(history);
            });
            if (taskRow != null) {
                taskRepository.delete(taskRow);
            }
            log.info("Task {} moved to history with status {}", taskId, finalStatus);
        } catch (RuntimeException e) {
            log.error("Failed to move task {} to history", taskId, e);
            if (com.loadtest.execution.util.DatabaseAvailabilityService.isDatabaseAccessFailure(e)) {
                throw e;
            }
        }
    }

    private boolean claimTaskRespectingDockerConcurrency(UUID taskId, OffsetDateTime now) throws InterruptedException {
        while (true) {
            int updated = entityManager.createNativeQuery("""
                            UPDATE test_task t
                            SET status = :processing, updated_at = :now,
                                locked_at = :now, locked_by = 'execution-service'
                            FROM docker_execution_profile p
                            WHERE t.id = :id AND t.status = :pending
                              AND t.docker_execution_profile_id = p.id
                              AND (
                                SELECT COUNT(*) FROM test_task x
                                WHERE x.docker_execution_profile_id = t.docker_execution_profile_id
                                  AND x.status = :processing
                              ) < p.max_concurrent_containers
                            """)
                    .setParameter("id", taskId)
                    .setParameter("now", now)
                    .setParameter("processing", STATUS_PROCESSING)
                    .setParameter("pending", STATUS_PENDING)
                    .executeUpdate();
            if (updated > 0) {
                return true;
            }
            TestTaskEntity row = taskRepository.findById(taskId).orElse(null);
            if (row == null) {
                return false;
            }
            if (row.getStatus() != TestTaskStatus.PENDING) {
                return false;
            }
            log.info("Docker profile concurrency full for task {}, retry in {} ms", taskId, DOCKER_SLOT_WAIT_MS);
            sleepForDockerRetry();
        }
    }

    protected void sleepForDockerRetry() throws InterruptedException {
        Thread.sleep(DOCKER_SLOT_WAIT_MS);
    }
}
