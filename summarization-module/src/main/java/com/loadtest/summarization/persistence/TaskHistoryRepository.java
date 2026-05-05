package com.loadtest.summarization.persistence;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@Repository
public class TaskHistoryRepository {

    private final JdbcTemplate jdbcTemplate;

    public TaskHistoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<String> getSummarizerNameByTaskId(UUID taskId) {
        String sql = "SELECT summarizer_name FROM test_task_history WHERE id = ?";
        try {
            return jdbcTemplate.query(sql, rs -> {
                if (rs.next()) {
                    String name = rs.getString("summarizer_name");
                    return Optional.ofNullable(name != null && !name.isBlank() ? name : null);
                }
                return Optional.empty();
            }, taskId);
        } catch (Exception e) {
            log.warn("Failed to load summarizer_name for taskId: {}", taskId, e);
            return Optional.empty();
        }
    }
}
