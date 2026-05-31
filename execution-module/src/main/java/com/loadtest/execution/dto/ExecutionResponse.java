package com.loadtest.execution.dto;

public record ExecutionResponse(
        String status,
        String message,
        String containerId,
        String containerName,
        String artifactBaseName,
        Long executionTime,
        String reportsHostPath,
        String metricsHostPath) {
}
