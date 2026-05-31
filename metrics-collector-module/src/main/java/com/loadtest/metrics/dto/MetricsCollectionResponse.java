package com.loadtest.metrics.dto;

import java.util.Map;

public record MetricsCollectionResponse(
        String taskId,
        String status,
        String message,
        Map<String, Object> metrics,
        SummaryResult summary,
        Long collectionStartTime,
        Long collectionEndTime) {

    public record SummaryResult(String status, String summary, Map<String, Object> details) {
    }
}
