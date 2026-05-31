package com.loadtest.app.service;

import com.loadtest.app.dto.TestTaskEvent;
import com.loadtest.app.dto.TestTaskMessage;
import com.loadtest.app.persistence.DockerExecutionProfileEntity;
import com.loadtest.app.persistence.DockerExecutionProfileRepository;
import com.loadtest.app.persistence.TestTaskHistoryEntity;
import com.loadtest.app.persistence.TestTaskHistoryRepository;
import com.loadtest.app.persistence.TestTaskRepository;
import com.loadtest.app.persistence.TestTaskStatus;
import com.loadtest.app.util.NativeQueryParams;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
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

    private static final int DEFAULT_EXPECTED_DURATION_SECONDS = 60;

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
                              UUID dockerExecutionProfileId) {
        return doEnqueueTest(testTool, testFileName, testFileContent, command, expectedDurationSeconds,
                metricsConfig, metricsConfigJson, summarizerName, dockerExecutionProfileId);
    }

    @Transactional
    public String rerunFromHistory(UUID fromHistoryTaskId, String summarizerOverride) {
        TestTaskHistoryEntity history = historyRepository.findById(fromHistoryTaskId)
                .orElseThrow(() -> new IllegalArgumentException("History record not found: " + fromHistoryTaskId));
        return doEnqueueTest(
                history.getTestTool(),
                history.getTestFileName(),
                history.getTestFileContentBase64(),
                history.getCommand(),
                resolveExpectedDurationSeconds(history.getExpectedDurationSeconds()),
                null,
                history.getMetricsConfig(),
                resolveSummarizerForRerun(summarizerOverride, history.getSummarizerName()),
                history.getDockerExecutionProfileId());
    }

    private String doEnqueueTest(String testTool, String testFileName, String testFileContent,
                                 String command, Integer expectedDurationSeconds,
                                 TestTaskMessage.MetricsConfig metricsConfig, String metricsConfigJson,
                                 String summarizerName,
                                 UUID dockerExecutionProfileId) {
        String taskId = UUID.randomUUID().toString();
        logEnqueueDetails(taskId, testTool, testFileName, testFileContent, command, metricsConfig);

        UUID taskUuid = UUID.fromString(taskId);
        UUID profileId = resolveDockerExecutionProfileId(dockerExecutionProfileId);
        insertPendingTestTask(taskUuid, testTool, testFileName, testFileContent, command,
                expectedDurationSeconds, metricsConfigJson, summarizerName, profileId);
        registerTestTaskKafkaDispatchAfterCommit(taskId, taskUuid);
        return taskId;
    }

    private void logEnqueueDetails(String taskId, String testTool, String testFileName, String testFileContent,
                                   String command, TestTaskMessage.MetricsConfig metricsConfig) {
        log.info("=== Enqueuing test task to Postgres queue ===");
        log.info("TaskId: {}", taskId);
        log.info("Tool: {}", testTool);
        log.info("FileName: {}", testFileName);
        log.info("FileContent size: {} bytes (Base64: {} chars)",
                testFileContent != null ? Base64.getDecoder().decode(testFileContent).length : 0,
                testFileContent != null ? testFileContent.length() : 0);
        log.info("Command: {}", command != null ? command : "not specified");
        logMetricsConfig(metricsConfig);
        log.info("=====================================");
    }

    private void logMetricsConfig(TestTaskMessage.MetricsConfig metricsConfig) {
        if (metricsConfig == null) {
            log.info("MetricsConfig: NOT CONFIGURED");
            return;
        }
        log.info("MetricsConfig: CONFIGURED, delaySeconds={}, requests={}",
                metricsConfig.delaySeconds(),
                metricsConfig.requests() != null ? metricsConfig.requests().size() : 0);
        if (metricsConfig.requests() == null) {
            return;
        }
        for (int i = 0; i < metricsConfig.requests().size(); i++) {
            TestTaskMessage.MetricsConfig.MetricsRequest req = metricsConfig.requests().get(i);
            log.info("  Request[{}]: name={}, method={}, url={}", i, req.name(), req.method(), req.url());
        }
    }

    private UUID resolveDockerExecutionProfileId(UUID dockerExecutionProfileId) {
        if (dockerExecutionProfileId != null) {
            return dockerExecutionProfileId;
        }
        return dockerExecutionProfileRepository
                .findFirstByNameAndEnabledTrue(DockerExecutionProfileService.DEFAULT_PROFILE_NAME)
                .or(dockerExecutionProfileRepository::findFirstByEnabledTrueOrderByCreatedAtAsc)
                .map(DockerExecutionProfileEntity::getId)
                .orElseThrow(() -> new IllegalStateException("No docker execution profile in database"));
    }

    private void insertPendingTestTask(UUID taskUuid, String testTool, String testFileName, String testFileContent,
                                       String command, Integer expectedDurationSeconds, String metricsConfigJson,
                                       String summarizerName, UUID profileId) {
        OffsetDateTime now = OffsetDateTime.now();
        String sql = """
            INSERT INTO test_task (
                id, status, created_at, updated_at, locked_at, locked_by,
                test_tool, test_file_name, test_file_content_base64,
                command, expected_duration_seconds, metrics_config, error_message, summarizer_name,
                docker_execution_profile_id
            ) VALUES (
                :%s, :%s, :%s, :%s, NULL, NULL,
                :%s, :%s, :%s,
                :%s, :%s, CAST(:%s AS jsonb), NULL, :%s,
                :%s
            )
            """.formatted(
                NativeQueryParams.ID,
                NativeQueryParams.STATUS,
                NativeQueryParams.CREATED_AT,
                NativeQueryParams.UPDATED_AT,
                NativeQueryParams.TEST_TOOL,
                NativeQueryParams.TEST_FILE_NAME,
                NativeQueryParams.TEST_FILE_CONTENT,
                NativeQueryParams.COMMAND,
                NativeQueryParams.EXPECTED_DURATION_SECONDS,
                NativeQueryParams.METRICS_CONFIG,
                NativeQueryParams.SUMMARIZER_NAME,
                NativeQueryParams.DOCKER_PROFILE_ID);

        Query query = entityManager.createNativeQuery(sql);
        query.setParameter(NativeQueryParams.ID, taskUuid);
        query.setParameter(NativeQueryParams.STATUS, TestTaskStatus.PENDING.name());
        query.setParameter(NativeQueryParams.CREATED_AT, now);
        query.setParameter(NativeQueryParams.UPDATED_AT, now);
        query.setParameter(NativeQueryParams.TEST_TOOL, testTool);
        query.setParameter(NativeQueryParams.TEST_FILE_NAME, testFileName);
        query.setParameter(NativeQueryParams.TEST_FILE_CONTENT, testFileContent);
        query.setParameter(NativeQueryParams.COMMAND, command != null ? command : "");
        query.setParameter(NativeQueryParams.EXPECTED_DURATION_SECONDS, expectedDurationSeconds);
        query.setParameter(NativeQueryParams.METRICS_CONFIG, normalizedMetricsConfigJson(metricsConfigJson));
        query.setParameter(NativeQueryParams.SUMMARIZER_NAME, summarizerName);
        query.setParameter(NativeQueryParams.DOCKER_PROFILE_ID, profileId);
        query.executeUpdate();

        log.info("✓ Test task {} successfully saved to PostgreSQL (table test_task). Status=PENDING", taskUuid);
    }

    private static String normalizedMetricsConfigJson(String metricsConfigJson) {
        if (metricsConfigJson == null || metricsConfigJson.trim().isEmpty()) {
            return null;
        }
        return metricsConfigJson;
    }

    private void registerTestTaskKafkaDispatchAfterCommit(String taskId, UUID taskUuid) {
        TestTaskEvent event = new TestTaskEvent(taskId);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                dispatchTestTaskEventAfterCommit(taskId, taskUuid, event);
            }
        });
    }

    private void dispatchTestTaskEventAfterCommit(String taskId, UUID taskUuid, TestTaskEvent event) {
        try {
            if (queuePauseService.isQueuePaused()) {
                queuePauseService.recordPendingKafkaDispatch(taskUuid);
                log.info("Queue paused: task {} saved for Kafka dispatch after unpause", taskId);
            } else {
                kafkaOutboxService.sendTestTaskEvent(taskId, event);
                log.info("✓ Test task event queued for Kafka topic '{}': taskId={}", testTasksTopic, taskId);
            }
        } catch (RuntimeException e) {
            log.error("Failed to queue test task event for Kafka/outbox task {}", taskId, e);
        }
    }

    private static int resolveExpectedDurationSeconds(Integer expectedDurationSeconds) {
        return expectedDurationSeconds != null ? expectedDurationSeconds : DEFAULT_EXPECTED_DURATION_SECONDS;
    }

    private static String resolveSummarizerForRerun(String summarizerOverride, String historySummarizer) {
        if (summarizerOverride != null && !summarizerOverride.isBlank()) {
            return summarizerOverride.trim();
        }
        return historySummarizer;
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
