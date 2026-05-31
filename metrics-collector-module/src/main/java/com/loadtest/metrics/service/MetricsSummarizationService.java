package com.loadtest.metrics.service;

import com.loadtest.metrics.dto.MetricsCollectionResponse;
import com.loadtest.metrics.util.TestSummaryConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
public class MetricsSummarizationService {

    public MetricsCollectionResponse.SummaryResult summarize(String taskId, Map<String, Object> metrics) {

        log.info("Starting metrics summarization for taskId: {}", taskId);

        try {
            String summary = generateSummary(metrics);
            Map<String, Object> details = generateDetails(metrics);

            log.info("Metrics summarization completed for taskId: {}", taskId);

            return new MetricsCollectionResponse.SummaryResult("SUCCESS", summary, details);

        } catch (RuntimeException e) {
            log.error("Error during metrics summarization for taskId: {}", taskId, e);

            return new MetricsCollectionResponse.SummaryResult(
                    TestSummaryConstants.STATUS_FAILED,
                    "Failed to summarize metrics: " + e.getMessage(),
                    null);
        }
    }

    private String generateSummary(Map<String, Object> metrics) {
        int endpointCount = metrics.size();
        int successCount = 0;

        for (Object value : metrics.values()) {
            if (value instanceof Map) {
                Map<?, ?> metricMap = (Map<?, ?>) value;
                if (!metricMap.containsKey("error")) {
                    successCount++;
                }
            }
        }

        return String.format(
                "Collected metrics from %d endpoint(s), %d successful, %d failed. "
                        + "Summary generation completed. "
                        + "Note: This is a placeholder implementation. "
                        + "Neural network integration for advanced analysis is planned for future releases.",
                endpointCount, successCount, endpointCount - successCount);
    }

    private Map<String, Object> generateDetails(Map<String, Object> metrics) {
        return Map.of(
                "totalEndpoints", metrics.size(),
                "collectionTimestamp", System.currentTimeMillis(),
                "note", "Detailed analysis will be available after neural network integration",
                "endpoints", metrics.keySet());
    }
}
