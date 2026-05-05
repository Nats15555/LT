package com.loadtest.summarization.persistence;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Repository
@RequiredArgsConstructor
public class TaskMetricsRepository {

    private final JdbcTemplate jdbcTemplate;

    public List<MetricsRow> findByTaskId(UUID taskId) {
        String sql = "SELECT source_type, endpoint_url, metrics_data, collected_at FROM test_metrics WHERE task_id = ? ORDER BY collected_at";
        List<MetricsRow> result = new ArrayList<>();
        try {
            jdbcTemplate.query(sql, rs -> {
                Timestamp ts = rs.getTimestamp("collected_at");
                result.add(new MetricsRow(
                        rs.getString("source_type"),
                        rs.getString("endpoint_url"),
                        rs.getString("metrics_data"),
                        ts != null ? ts.toInstant() : null
                ));
            }, taskId);
        } catch (Exception e) {
            log.warn("Failed to load metrics for taskId={}: {}", taskId, e.getMessage());
        }
        return result;
    }

    @Data
    public static class MetricsRow {
        private final String sourceType;
        private final String endpointUrl;
        private final String metricsDataJson;
        private final Instant collectedAt;
    }
}
