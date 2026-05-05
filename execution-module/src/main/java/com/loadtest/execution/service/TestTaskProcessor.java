package com.loadtest.execution.service;

import com.loadtest.execution.ContainerExecutionService;
import com.loadtest.execution.dto.ExecutionRequest;
import com.loadtest.execution.dto.ExecutionResponse;
import com.loadtest.execution.dto.TaskProcessOutcome;
import com.loadtest.execution.dto.TestTaskMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Base64;

@Slf4j
@Service
@RequiredArgsConstructor
public class TestTaskProcessor {

    private final ContainerExecutionService executionService;

    @Value("${file.storage.upload-dir:uploads}")
    private String uploadDir;

    static boolean shouldDeleteTemporaryTestFile(Path savedFilePath) {
        return savedFilePath != null && Files.exists(savedFilePath);
    }

    public TaskProcessOutcome process(TestTaskMessage message) throws Exception {
        log.info("=== Processing test task from Postgres queue ===");
        log.info("TaskId: {}", message.getTaskId());
        log.info("Tool: {}", message.getTestTool());
        log.info("FileName: {}", message.getTestFileName());
        log.info("FileContent size: {} chars (Base64)",
                message.getTestFileContent() != null ? message.getTestFileContent().length() : 0);
        if (message.getMetricsConfig() != null) {
            var metricsConfig = message.getMetricsConfig();
            log.info("MetricsConfig: CONFIGURED, delaySeconds={}, requests={}",
                    metricsConfig.getDelaySeconds(),
                    metricsConfig.getRequests() != null ? metricsConfig.getRequests().size() : 0);
        } else {
            log.info("MetricsConfig: NOT CONFIGURED");
        }
        log.info("=====================================");

        Path savedFilePath = null;
        try {
            if (message.getTestFileName() == null || message.getTestFileName().trim().isEmpty()) {
                throw new IllegalArgumentException("Test file name is required");
            }
            if (message.getTestFileContent() == null || message.getTestFileContent().trim().isEmpty()) {
                throw new IllegalArgumentException("Test file content is required");
            }
            if (message.getExpectedDurationSeconds() == null || message.getExpectedDurationSeconds() < 1) {
                throw new IllegalArgumentException("expectedDurationSeconds is required and must be at least 1 (from upload)");
            }

            byte[] fileBytes = Base64.getDecoder().decode(message.getTestFileContent());
            log.info("Decoded file content: {} bytes (from Base64: {} chars)",
                    fileBytes.length, message.getTestFileContent().length());

            Path uploadPath = Paths.get(uploadDir);
            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
            }

            savedFilePath = uploadPath.resolve(message.getTestFileName());
            Files.write(savedFilePath, fileBytes);
            log.info("✓ Test file saved to: {} ({} bytes)", savedFilePath.toAbsolutePath(), fileBytes.length);

            ExecutionRequest request = new ExecutionRequest();
            request.setTestTool(message.getTestTool());
            request.setCommand(message.getCommand());
            request.setTestFilePath(savedFilePath.toAbsolutePath().toString());
            request.setTaskId(java.util.UUID.fromString(message.getTaskId()));
            request.setExpectedDurationSeconds(message.getExpectedDurationSeconds());
            if (message.getDockerExecutionProfileId() == null || message.getDockerExecutionProfileId().isBlank()) {
                throw new IllegalArgumentException("dockerExecutionProfileId is required on task message");
            }
            request.setDockerExecutionProfileId(java.util.UUID.fromString(message.getDockerExecutionProfileId()));

            long testStartTime = System.currentTimeMillis();
            ExecutionResponse response = executionService.executeTestWithAutoCleanup(request);
            long testEndTime = System.currentTimeMillis();

            log.info("Test task {} completed successfully. Execution time: {}s. Metrics time range: {} .. {}",
                    message.getTaskId(),
                    response.getExecutionTime(),
                    Instant.ofEpochMilli(testStartTime),
                    Instant.ofEpochMilli(testEndTime));

            return new TaskProcessOutcome(response, testStartTime, testEndTime);
        } finally {
            if (shouldDeleteTemporaryTestFile(savedFilePath)) {
                try {
                    Files.delete(savedFilePath);
                    log.debug("Temporary test file deleted: {}", savedFilePath);
                } catch (Exception e) {
                    log.warn("Failed to delete temporary test file: {}", savedFilePath, e);
                }
            }
        }
    }
}

