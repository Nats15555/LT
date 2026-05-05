package com.loadtest.execution.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionResponse {
    
    private String status;
    private String message;
    private String containerId;
    private String containerName;

    private String artifactBaseName;
    private Long executionTime;

    private String reportsHostPath;
    private String metricsHostPath;
}
