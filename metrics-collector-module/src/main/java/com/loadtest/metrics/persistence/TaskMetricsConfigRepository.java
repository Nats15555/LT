package com.loadtest.metrics.persistence;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@Repository
public class TaskMetricsConfigRepository {

    private final JdbcTemplate jdbcTemplate;

    public TaskMetricsConfigRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<TaskMetricsConfig> findByTaskId(UUID taskId) {
        Optional<TaskMetricsConfig> fromTask = findFromTestTask(taskId);
        if (fromTask.isPresent()) {
            return fromTask;
        }
        return findFromTestTaskHistory(taskId);
    }

    public Optional<String> findSummarizerNameByTaskId(UUID taskId) {
        Optional<String> fromTask = querySummarizerName("SELECT summarizer_name FROM test_task WHERE id = ?", taskId);
        if (fromTask.isPresent() && fromTask.get() != null && !fromTask.get().isBlank()) {
            return fromTask;
        }
        return querySummarizerName("SELECT summarizer_name FROM test_task_history WHERE id = ?", taskId);
    }

    private Optional<String> querySummarizerName(String sql, UUID taskId) {
        try {
            return jdbcTemplate.query(sql, rs -> {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.ofNullable(rs.getString("summarizer_name"));
            }, taskId);
        } catch (Exception e) {
            log.warn("Failed to load summarizer_name for taskId: {}", taskId, e);
            return Optional.empty();
        }
    }

    private Optional<TaskMetricsConfig> findFromTestTask(UUID taskId) {
        String sql = "SELECT metrics_config FROM test_task WHERE id = ?";
        try {
            return jdbcTemplate.query(sql, rs -> {
                if (rs.next()) {
                    String metricsConfig = rs.getString("metrics_config");
                    return Optional.of(new TaskMetricsConfig(metricsConfig));
                }
                return Optional.empty();
            }, taskId);
        } catch (Exception e) {
            log.warn("Failed to load metrics config from test_task for taskId: {}", taskId, e);
            return Optional.empty();
        }
    }

    private Optional<TaskMetricsConfig> findFromTestTaskHistory(UUID taskId) {
        String sql = "SELECT metrics_config FROM test_task_history WHERE id = ?";
        try {
            return jdbcTemplate.query(sql, rs -> {
                if (rs.next()) {
                    String metricsConfig = rs.getString("metrics_config");
                    return Optional.of(new TaskMetricsConfig(metricsConfig));
                }
                return Optional.empty();
            }, taskId);
        } catch (Exception e) {
            log.warn("Failed to load metrics config from test_task_history for taskId: {}", taskId, e);
            return Optional.empty();
        }
    }

    public static final class TaskMetricsConfig {
        private final String metricsConfigJson;

        public TaskMetricsConfig(String metricsConfigJson) {
            this.metricsConfigJson = metricsConfigJson;
        }

        public String getMetricsConfigJson() {
            return metricsConfigJson;
        }
    }
}
