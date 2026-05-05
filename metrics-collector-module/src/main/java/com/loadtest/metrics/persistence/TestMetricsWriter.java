package com.loadtest.metrics.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadtest.metrics.dto.MetricsCollectionRequest;
import com.loadtest.metrics.dto.MetricsCollectionResponse;
import com.loadtest.metrics.service.MetricsCollectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class TestMetricsWriter {

    private static final int SOURCE_TYPE_MAX_LENGTH = 32;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final MetricsCollectionService metricsCollectionService;

    public int saveMetrics(String taskIdStr, MetricsCollectionRequest request, MetricsCollectionResponse response) {
        if (response.getMetrics() == null || response.getMetrics().isEmpty()) {
            return 0;
        }
        UUID taskId;
        try {
            taskId = UUID.fromString(taskIdStr);
        } catch (Exception e) {
            log.warn("Invalid taskId for metrics persistence: {}", taskIdStr, e);
            return 0;
        }
        int inserted = 0;
        for (MetricsCollectionRequest.MetricsRequestItem req : request.getRequests()) {
            String key = req.getName() != null && !req.getName().isBlank() ? req.getName() : req.getUrl();
            Object data = response.getMetrics().get(key);
            if (data == null) continue;
            String sourceType = key.length() > SOURCE_TYPE_MAX_LENGTH ? key.substring(0, SOURCE_TYPE_MAX_LENGTH) : key;
            String endpointUrl = metricsCollectionService.getEffectiveUrl(req);
            String queryParams = req.getQueryParams() == null ? null : (req.getQueryParams() instanceof String ? (String) req.getQueryParams() : toJson(req.getQueryParams()));
            String metricsDataJson;
            try {
                metricsDataJson = data instanceof Map ? objectMapper.writeValueAsString(data) : objectMapper.writeValueAsString(Map.of("_raw", data));
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize metrics data for {}: {}", key, e.getMessage());
                continue;
            }
            UUID id = UUID.randomUUID();
            OffsetDateTime collectedAt = OffsetDateTime.now();
            try {
                jdbcTemplate.update(
                        "INSERT INTO test_metrics (id, task_id, source_type, endpoint_url, query_params, metrics_data, collected_at) VALUES (?, ?::uuid, ?, ?, ?, ?::jsonb, ?)",
                        id, taskId, sourceType, endpointUrl, queryParams, metricsDataJson, collectedAt);
                inserted++;
                log.info("Saved metrics to test_metrics: taskId={}, sourceType={}", taskId, sourceType);
            } catch (Exception e) {
                log.error("Failed to insert test_metrics for taskId={}, sourceType={}: {}", taskId, sourceType, e.getMessage());
            }
        }
        return inserted;
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            return String.valueOf(o);
        }
    }
}
