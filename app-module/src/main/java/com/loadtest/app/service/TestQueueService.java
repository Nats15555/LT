package com.loadtest.app.service;

import com.loadtest.app.dto.TestTaskEvent;
import com.loadtest.app.dto.TestTaskMessage;
import com.loadtest.app.persistence.DockerExecutionProfileEntity;
import com.loadtest.app.persistence.DockerExecutionProfileRepository;
import com.loadtest.app.persistence.TestTaskHistoryEntity;
import com.loadtest.app.persistence.TestTaskHistoryRepository;
import com.loadtest.app.persistence.TestTaskRepository;
import com.loadtest.app.persistence.TestTaskStatus;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Base64;
import java.util.UUID;
import java.time.OffsetDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class TestQueueService {

    public enum DeletePendingQueueTaskOutcome {
        DELETED,
        NOT_FOUND,
        NOT_DELETABLE
    }

    private final EntityManager entityManager;
    private final KafkaOutboxService kafkaOutboxService;
    private final QueuePauseService queuePauseService;
    private final TestTaskHistoryRepository historyRepository;
    private final TestTaskRepository taskRepository;
    private final DockerExecutionProfileRepository dockerExecutionProfileRepository;

    @Value("${kafka.topic.test-tasks:test-tasks}")
    private String testTasksTopic;

    @Transactional
    public String enqueueTest(String testTool, String testFileName, String testFileContent,
                              String command, Integer expectedDurationSeconds,
                              TestTaskMessage.MetricsConfig metricsConfig, String metricsConfigJson,
                              String summarizerName,
                              java.util.UUID dockerExecutionProfileId) {
        String taskId = UUID.randomUUID().toString();

        log.info("=== Enqueuing test task to Postgres queue ===");
        log.info("TaskId: {}", taskId);
        log.info("Tool: {}", testTool);
        log.info("FileName: {}", testFileName);
        log.info("FileContent size: {} bytes (Base64: {} chars)", 
                testFileContent != null ? Base64.getDecoder().decode(testFileContent).length : 0,
                testFileContent != null ? testFileContent.length() : 0);
        log.info("Command: {}", command != null ? command : "not specified");
        
        if (metricsConfig != null) {
            log.info("MetricsConfig: CONFIGURED, delaySeconds={}, requests={}",
                    metricsConfig.getDelaySeconds(),
                    metricsConfig.getRequests() != null ? metricsConfig.getRequests().size() : 0);
            if (metricsConfig.getRequests() != null) {
                for (int i = 0; i < metricsConfig.getRequests().size(); i++) {
                    TestTaskMessage.MetricsConfig.MetricsRequest req = metricsConfig.getRequests().get(i);
                    log.info("  Request[{}]: name={}, method={}, url={}", i, req.getName(), req.getMethod(), req.getUrl());
                }
            }
        } else {
            log.info("MetricsConfig: NOT CONFIGURED");
        }
        log.info("=====================================");

        OffsetDateTime now = OffsetDateTime.now();
        UUID taskUuid = UUID.fromString(taskId);

        UUID profileId = dockerExecutionProfileId != null ? dockerExecutionProfileId
                : dockerExecutionProfileRepository
                .findFirstByNameAndEnabledTrue(DockerExecutionProfileService.DEFAULT_PROFILE_NAME)
                .or(() -> dockerExecutionProfileRepository.findFirstByEnabledTrueOrderByCreatedAtAsc())
                .map(DockerExecutionProfileEntity::getId)
                .orElseThrow(() -> new IllegalStateException("No docker execution profile in database"));
        
        String sql = """
            INSERT INTO test_task (
                id, status, created_at, updated_at, locked_at, locked_by,
                test_tool, test_file_name, test_file_content_base64,
                command, expected_duration_seconds, metrics_config, error_message, summarizer_name,
                docker_execution_profile_id
            ) VALUES (
                :id, :status, :createdAt, :updatedAt, NULL, NULL,
                :testTool, :testFileName, :testFileContent,
                :command, :expectedDurationSeconds, CAST(:metricsConfig AS jsonb), NULL, :summarizerName,
                :dockerProfileId
            )
            """;
        
        entityManager.createNativeQuery(sql)
                .setParameter("id", taskUuid)
                .setParameter("status", TestTaskStatus.PENDING.name())
                .setParameter("createdAt", now)
                .setParameter("updatedAt", now)
                .setParameter("testTool", testTool)
                .setParameter("testFileName", testFileName)
                .setParameter("testFileContent", testFileContent)
                .setParameter("command", command != null ? command : "")
                .setParameter("expectedDurationSeconds", expectedDurationSeconds)
                .setParameter("metricsConfig", 
                        metricsConfigJson != null && !metricsConfigJson.trim().isEmpty() 
                                ? metricsConfigJson : null)
                .setParameter("summarizerName", summarizerName)
                .setParameter("dockerProfileId", profileId)
                .executeUpdate();
        
        log.info("✓ Test task {} successfully saved to PostgreSQL (table test_task). Status=PENDING", taskId);

        TestTaskEvent event = TestTaskEvent.builder().taskId(taskId).build();
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            if (queuePauseService.isQueuePaused()) {
                                queuePauseService.recordPendingKafkaDispatch(taskUuid);
                                log.info("Queue paused: task {} saved for Kafka dispatch after unpause", taskId);
                            } else {
                                kafkaOutboxService.sendTestTaskEvent(taskId, event);
                                log.info("✓ Test task event queued for Kafka topic '{}': taskId={}", testTasksTopic, taskId);
                            }
                        } catch (Exception e) {
                            log.error("Failed to queue test task event for Kafka/outbox task {}", taskId, e);
                        }
                    }
                });
        
        return taskId;
    }

    @Transactional
    public String rerunFromHistory(UUID fromHistoryTaskId, String summarizerOverride) {
        TestTaskHistoryEntity history = historyRepository.findById(fromHistoryTaskId)
                .orElseThrow(() -> new IllegalArgumentException("History record not found: " + fromHistoryTaskId));
        Integer expectedDuration = history.getExpectedDurationSeconds() != null
                ? history.getExpectedDurationSeconds() : 60;
        String summarizer = (summarizerOverride != null && !summarizerOverride.isBlank())
                ? summarizerOverride.trim()
                : history.getSummarizerName();
        return enqueueTest(
                history.getTestTool(),
                history.getTestFileName(),
                history.getTestFileContentBase64(),
                history.getCommand(),
                expectedDuration,
                null,
                history.getMetricsConfig(),
                summarizer,
                history.getDockerExecutionProfileId()
        );
    }

    @Transactional
    public DeletePendingQueueTaskOutcome deletePendingQueueTask(UUID taskId) {
        int removed = taskRepository.deleteByIdIfStatusPending(taskId);
        if (removed > 0) {
            log.info("Deleted pending task {} from test_task (Kafka event may still be consumed; execution will no-op).",
                    taskId);
            return DeletePendingQueueTaskOutcome.DELETED;
        }
        if (!taskRepository.existsById(taskId)) {
            return DeletePendingQueueTaskOutcome.NOT_FOUND;
        }
        return DeletePendingQueueTaskOutcome.NOT_DELETABLE;
    }

    @Transactional
    public boolean deleteHistoryRun(UUID taskId) {
        if (!historyRepository.existsById(taskId)) {
            return false;
        }
        historyRepository.deleteById(taskId);
        log.info("Deleted test_task_history {} (dependent rows via ON DELETE CASCADE).", taskId);
        return true;
    }
}


