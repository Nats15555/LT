package com.loadtest.metrics.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetricsCollectionRequest {

    @NotNull(message = "Task ID is required")
    private String taskId;

    @NotEmpty(message = "At least one request is required")
    private List<MetricsRequestItem> requests;

    @Min(value = 0, message = "Delay must be non-negative")
    private Integer delaySeconds = 0;

    private Long testStartTime;
    private Long testEndTime;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MetricsRequestItem {
        private String name;
        private String method = "GET";
        @NotNull
        private String url;
        private Map<String, String> headers;
        private Object queryParams;
        private Object body;
    }
}
