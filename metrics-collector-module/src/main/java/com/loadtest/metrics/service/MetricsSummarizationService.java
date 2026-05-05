package com.loadtest.metrics.service;

import com.loadtest.metrics.dto.MetricsCollectionResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
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

            return MetricsCollectionResponse.SummaryResult.builder()
                    .status("SUCCESS")
                    .summary(summary)
                    .details(details)
                    .build();

        } catch (Exception e) {
            log.error("Error during metrics summarization for taskId: {}", taskId, e);

            return MetricsCollectionResponse.SummaryResult.builder()
                    .status("FAILED")
                    .summary("Failed to summarize metrics: " + e.getMessage())
                    .build();
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
        Map<String, Object> details = new HashMap<>();
        details.put("totalEndpoints", metrics.size());
        details.put("collectionTimestamp", System.currentTimeMillis());
        details.put("note", "Detailed analysis will be available after neural network integration");
        details.put("endpoints", metrics.keySet());
        return details;
    }
}
