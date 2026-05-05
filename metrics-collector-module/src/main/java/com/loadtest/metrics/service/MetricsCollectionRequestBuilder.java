package com.loadtest.metrics.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadtest.metrics.dto.MetricsCollectionEvent;
import com.loadtest.metrics.dto.MetricsCollectionRequest;
import com.loadtest.metrics.persistence.TaskMetricsConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class MetricsCollectionRequestBuilder {

    private final TaskMetricsConfigRepository taskMetricsConfigRepository;
    private final ObjectMapper objectMapper;

    public MetricsCollectionRequest buildFromEvent(MetricsCollectionEvent event) {
        return tryBuildFromEvent(event).orElseThrow(() ->
                new IllegalArgumentException("Invalid or incomplete metrics configuration for taskId: " + event.getTaskId()));
    }

    public Optional<MetricsCollectionRequest> tryBuildFromEvent(MetricsCollectionEvent event) {
        UUID taskId = UUID.fromString(event.getTaskId());
        Optional<TaskMetricsConfigRepository.TaskMetricsConfig> configOpt = taskMetricsConfigRepository.findByTaskId(taskId);
        if (configOpt.isEmpty()) {
            log.warn("Metrics config not found for taskId: {}", event.getTaskId());
            return Optional.empty();
        }
        TaskMetricsConfigRepository.TaskMetricsConfig config = configOpt.get();

        String metricsConfigJson = config.getMetricsConfigJson();
        if (metricsConfigJson == null || metricsConfigJson.isBlank()) {
            log.warn("Empty metrics_config for taskId: {}", event.getTaskId());
            return Optional.empty();
        }

        long startMs = event.getTestStartTime() != null ? event.getTestStartTime() : 0L;
        long endMs = event.getTestEndTime() != null ? event.getTestEndTime() : 0L;
        String replacedJson = MetricsConfigPlaceholderReplacer.replace(metricsConfigJson, startMs, endMs);

        Map<String, Object> root;
        try {
            root = objectMapper.readValue(replacedJson, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Failed to parse metrics_config for taskId={}: {}", event.getTaskId(), e.getMessage());
            return Optional.empty();
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> requestsList = (List<Map<String, Object>>) root.get("requests");
        if (requestsList == null || requestsList.isEmpty()) {
            log.warn("metrics_config.requests is empty for taskId: {}", event.getTaskId());
            return Optional.empty();
        }

        Integer delaySeconds = root.get("delaySeconds") != null
                ? ((Number) root.get("delaySeconds")).intValue() : 0;

        List<MetricsCollectionRequest.MetricsRequestItem> items = new ArrayList<>();
        for (Map<String, Object> reqMap : requestsList) {
            MetricsCollectionRequest.MetricsRequestItem item = objectMapper.convertValue(
                    reqMap, MetricsCollectionRequest.MetricsRequestItem.class);
            items.add(item);
        }

        return Optional.of(MetricsCollectionRequest.builder()
                .taskId(event.getTaskId())
                .delaySeconds(delaySeconds)
                .testStartTime(event.getTestStartTime())
                .testEndTime(event.getTestEndTime())
                .requests(items)
                .build());
    }
}
