package com.loadtest.execution.dto;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

public record TestTaskMessage(
        String taskId,
        String testTool,
        String testFileName,
        String testFileContent,
        String command,
        Integer expectedDurationSeconds,
        String status,
        Long timestamp,
        MetricsConfig metricsConfig,
        String dockerExecutionProfileId) implements Serializable {

    public record MetricsConfig(Integer delaySeconds, List<MetricsRequest> requests) implements Serializable {
        public MetricsConfig {
            if (delaySeconds == null) {
                delaySeconds = 0;
            }
        }

        public record MetricsRequest(
                String name,
                String method,
                String url,
                Map<String, String> headers,
                Object queryParams,
                Object body) implements Serializable {
            public MetricsRequest {
                if (method == null || method.isBlank()) {
                    method = "GET";
                }
            }
        }
    }
}
