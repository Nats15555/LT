package com.loadtest.metrics.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadtest.metrics.dto.MetricsCollectionRequest;
import com.loadtest.metrics.dto.MetricsCollectionResponse;
import com.loadtest.metrics.service.MetricsCollectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class TestMetricsWriter {

    private static final int SOURCE_TYPE_MAX_LENGTH = 32;

    private final TestMetricsJpaRepository testMetricsJpaRepository;
    private final ObjectMapper objectMapper;
    private final MetricsCollectionService metricsCollectionService;

    public int saveMetrics(String taskIdStr, MetricsCollectionRequest request, MetricsCollectionResponse response) {
        if (!hasPersistableMetrics(response)) {
            return 0;
        }
        UUID taskId = parseTaskIdOrNull(taskIdStr);
        if (taskId == null) {
            return 0;
        }
        int inserted = 0;
        for (MetricsCollectionRequest.MetricsRequestItem req : request.requests()) {
            if (persistMetricRow(taskId, req, response.metrics())) {
                inserted++;
            }
        }
        return inserted;
    }

    private static boolean hasPersistableMetrics(MetricsCollectionResponse response) {
        return response.metrics() != null && !response.metrics().isEmpty();
    }

    private UUID parseTaskIdOrNull(String taskIdStr) {
        try {
            return UUID.fromString(taskIdStr);
        } catch (RuntimeException e) {
            log.warn("Invalid taskId for metrics persistence: {}", taskIdStr, e);
            return null;
        }
    }

    private boolean persistMetricRow(
            UUID taskId,
            MetricsCollectionRequest.MetricsRequestItem req,
            Map<String, Object> metricsByKey) {
        String key = metricKey(req);
        Object data = metricsByKey.get(key);
        if (data == null) {
            return false;
        }
        String endpointUrl = metricsCollectionService.getEffectiveUrl(req);
        String queryParams = serializeQueryParams(req);
        String metricsDataJson = serializeMetricsData(data, key);
        if (metricsDataJson == null) {
            return false;
        }
        return insertMetricEntity(taskId, key, endpointUrl, queryParams, metricsDataJson);
    }

    private static String metricKey(MetricsCollectionRequest.MetricsRequestItem req) {
        return req.name() != null && !req.name().isBlank() ? req.name() : req.url();
    }

    private String serializeMetricsData(Object data, String key) {
        try {
            if (data instanceof Map) {
                return objectMapper.writeValueAsString(data);
            }
            return objectMapper.writeValueAsString(Map.of("_raw", data));
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize metrics data for {}: {}", key, e.getMessage());
            return null;
        }
    }

    private boolean insertMetricEntity(
            UUID taskId,
            String key,
            String endpointUrl,
            String queryParams,
            String metricsDataJson) {
        String sourceType = truncateSourceType(key);
        if (testMetricsJpaRepository.existsByTaskIdAndSourceType(taskId, sourceType)) {
            log.info("Metrics already saved: taskId={}, sourceType={}, skipping duplicate", taskId, sourceType);
            return true;
        }
        try {
            testMetricsJpaRepository.save(TestMetricsEntity.builder()
                    .id(UUID.randomUUID())
                    .taskId(taskId)
                    .sourceType(sourceType)
                    .endpointUrl(endpointUrl)
                    .queryParams(queryParams)
                    .metricsData(metricsDataJson)
                    .collectedAt(OffsetDateTime.now())
                    .build());
            log.info("Saved metrics to test_metrics: taskId={}, sourceType={}", taskId, sourceType);
            return true;
        } catch (RuntimeException e) {
            log.error("Failed to insert test_metrics for taskId={}, sourceType={}: {}", taskId, sourceType, e.getMessage());
            throw new TestMetricsPersistenceException(
                    "Failed to insert test_metrics for taskId=" + taskId + ", sourceType=" + sourceType, e);
        }
    }

    public static class TestMetricsPersistenceException extends RuntimeException {

        public TestMetricsPersistenceException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static String truncateSourceType(String key) {
        return key.length() > SOURCE_TYPE_MAX_LENGTH ? key.substring(0, SOURCE_TYPE_MAX_LENGTH) : key;
    }

    private String serializeQueryParams(MetricsCollectionRequest.MetricsRequestItem req) {
        if (req.queryParams() == null) {
            return null;
        }
        if (req.queryParams() instanceof String s) {
            return s;
        }
        return toJson(req.queryParams());
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            return String.valueOf(o);
        }
    }
}
