package com.loadtest.metrics.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetricsCollectionResponse {

    private String taskId;

    private String status; // SUCCESS, FAILED, PARTIAL

    private String message;

    private Map<String, Object> metrics;

    private SummaryResult summary;

    private Long collectionStartTime;

    private Long collectionEndTime;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SummaryResult {
        private String status; // SUCCESS, FAILED, NOT_ENABLED
        private String summary;
        private Map<String, Object> details;
    }
}


