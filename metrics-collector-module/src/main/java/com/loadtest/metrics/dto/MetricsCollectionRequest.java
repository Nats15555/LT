package com.loadtest.metrics.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

public record MetricsCollectionRequest(
        @NotNull(message = "Task ID is required") String taskId,
        @NotEmpty(message = "At least one request is required") List<MetricsRequestItem> requests,
        @Min(value = 0, message = "Delay must be non-negative") Integer delaySeconds,
        Long testStartTime,
        Long testEndTime) {

    public MetricsCollectionRequest {
        if (delaySeconds == null) {
            delaySeconds = 0;
        }
    }

    public record MetricsRequestItem(
            String name,
            String method,
            @NotNull String url,
            Map<String, String> headers,
            Object queryParams,
            Object body) {
        public MetricsRequestItem {
            if (method == null || method.isBlank()) {
                method = "GET";
            }
        }
    }
}
