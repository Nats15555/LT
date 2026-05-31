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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

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

    public TaskProcessOutcome process(TestTaskMessage message) {
        logTaskDetails(message);
        Path savedFilePath = null;
        try {
            validateProcessMessage(message);
            savedFilePath = saveDecodedTestFile(message);
            return executeTestTask(message, savedFilePath);
        } catch (IOException e) {
            throw new TestTaskProcessException("Failed to prepare or run test task " + message.taskId(), e);
        } finally {
            deleteTemporaryTestFileQuietly(savedFilePath);
        }
    }

    private void logTaskDetails(TestTaskMessage message) {
        log.info("=== Processing test task from Postgres queue ===");
        log.info("TaskId: {}", message.taskId());
        log.info("Tool: {}", message.testTool());
        log.info("FileName: {}", message.testFileName());
        log.info("FileContent size: {} chars (Base64)",
                message.testFileContent() != null ? message.testFileContent().length() : 0);
        if (message.metricsConfig() != null) {
            var metricsConfig = message.metricsConfig();
            log.info("MetricsConfig: CONFIGURED, delaySeconds={}, requests={}",
                    metricsConfig.delaySeconds(),
                    metricsConfig.requests() != null ? metricsConfig.requests().size() : 0);
        } else {
            log.info("MetricsConfig: NOT CONFIGURED");
        }
        log.info("=====================================");
    }

    private static void validateProcessMessage(TestTaskMessage message) {
        if (message.testFileName() == null || message.testFileName().trim().isEmpty()) {
            throw new IllegalArgumentException("Test file name is required");
        }
        if (message.testFileContent() == null || message.testFileContent().trim().isEmpty()) {
            throw new IllegalArgumentException("Test file content is required");
        }
        if (message.expectedDurationSeconds() == null || message.expectedDurationSeconds() < 1) {
            throw new IllegalArgumentException("expectedDurationSeconds is required and must be at least 1 (from upload)");
        }
        if (message.dockerExecutionProfileId() == null || message.dockerExecutionProfileId().isBlank()) {
            throw new IllegalArgumentException("dockerExecutionProfileId is required on task message");
        }
    }

    private Path saveDecodedTestFile(TestTaskMessage message) throws IOException {
        byte[] fileBytes = Base64.getDecoder().decode(message.testFileContent());
        log.info("Decoded file content: {} bytes (from Base64: {} chars)",
                fileBytes.length, message.testFileContent().length());

        Path uploadPath = Paths.get(uploadDir);
        if (!Files.exists(uploadPath)) {
            Files.createDirectories(uploadPath);
        }

        Path savedFilePath = uploadPath.resolve(message.testFileName());
        Files.write(savedFilePath, fileBytes);
        log.info("✓ Test file saved to: {} ({} bytes)", savedFilePath.toAbsolutePath(), fileBytes.length);
        return savedFilePath;
    }

    private TaskProcessOutcome executeTestTask(TestTaskMessage message, Path savedFilePath) {
        ExecutionRequest request = new ExecutionRequest(
                message.testTool(),
                message.command(),
                savedFilePath.toAbsolutePath().toString(),
                UUID.fromString(message.taskId()),
                message.expectedDurationSeconds(),
                UUID.fromString(message.dockerExecutionProfileId()));

        long testStartTime = System.currentTimeMillis();
        ExecutionResponse response = executionService.executeTestWithAutoCleanup(request);
        long testEndTime = System.currentTimeMillis();

        log.info("Test task {} completed successfully. Execution time: {}s. Metrics time range: {} .. {}",
                message.taskId(),
                response.executionTime(),
                Instant.ofEpochMilli(testStartTime),
                Instant.ofEpochMilli(testEndTime));

        return new TaskProcessOutcome(response, testStartTime, testEndTime);
    }

    private void deleteTemporaryTestFileQuietly(Path savedFilePath) {
        if (!shouldDeleteTemporaryTestFile(savedFilePath)) {
            return;
        }
        try {
            Files.delete(savedFilePath);
            log.debug("Temporary test file deleted: {}", savedFilePath);
        } catch (IOException e) {
            log.warn("Failed to delete temporary test file: {}", savedFilePath, e);
        }
    }

    public static class TestTaskProcessException extends RuntimeException {

        public TestTaskProcessException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
