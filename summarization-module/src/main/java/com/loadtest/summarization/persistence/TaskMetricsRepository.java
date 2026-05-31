package com.loadtest.summarization.persistence;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Repository
@RequiredArgsConstructor
public class TaskMetricsRepository {

    private final TestMetricsJpaRepository testMetricsJpaRepository;

    public List<MetricsRow> findByTaskId(UUID taskId) {
        try {
            return testMetricsJpaRepository.findByTaskIdOrderByCollectedAtAsc(taskId).stream()
                    .map(entity -> new MetricsRow(
                            entity.getSourceType(),
                            entity.getEndpointUrl(),
                            entity.getMetricsData(),
                            entity.getCollectedAt() != null ? entity.getCollectedAt().toInstant() : null))
                    .toList();
        } catch (RuntimeException e) {
            log.warn("Failed to load metrics for taskId={}: {}", taskId, e.getMessage());
            return new ArrayList<>();
        }
    }

    @Data
    public static class MetricsRow {
        private final String sourceType;
        private final String endpointUrl;
        private final String metricsDataJson;
        private final Instant collectedAt;
    }
}
