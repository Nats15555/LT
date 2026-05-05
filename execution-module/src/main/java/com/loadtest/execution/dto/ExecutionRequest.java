package com.loadtest.execution.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionRequest {

    @NotNull(message = "Test tool is required")
    private String testTool;

    @NotBlank(message = "Command is required")
    private String command;

    @NotBlank(message = "Test file path is required")
    private String testFilePath;

    private UUID taskId;

    private Integer expectedDurationSeconds;

    private UUID dockerExecutionProfileId;
}
