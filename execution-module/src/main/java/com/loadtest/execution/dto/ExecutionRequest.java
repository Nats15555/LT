package com.loadtest.execution.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record ExecutionRequest(
        @NotNull(message = "Test tool is required") String testTool,
        @NotBlank(message = "Command is required") String command,
        @NotBlank(message = "Test file path is required") String testFilePath,
        UUID taskId,
        Integer expectedDurationSeconds,
        UUID dockerExecutionProfileId) {
}
