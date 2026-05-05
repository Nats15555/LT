package com.loadtest.app.service;

import com.loadtest.app.dto.TestTaskEvent;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueuePauseService {

    private final JdbcTemplate jdbcTemplate;
    private final KafkaOutboxService kafkaOutboxService;

    @PostConstruct
    public void ensureSchema() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS loadtest_system_settings (
                    id INTEGER PRIMARY KEY DEFAULT 1 CHECK (id = 1),
                    queue_paused BOOLEAN NOT NULL DEFAULT false,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """);
        jdbcTemplate.execute("""
                INSERT INTO loadtest_system_settings (id, queue_paused) VALUES (1, false)
                ON CONFLICT (id) DO NOTHING
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS test_task_kafka_pending (
                    task_id UUID PRIMARY KEY REFERENCES test_task(id) ON DELETE CASCADE,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """);
        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_test_task_kafka_pending_created_at
                ON test_task_kafka_pending(created_at)
                """);
    }

    public boolean isQueuePaused() {
        try {
            return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                    "SELECT queue_paused FROM loadtest_system_settings WHERE id = 1", Boolean.class));
        } catch (Exception e) {
            return false;
        }
    }

    public long countPendingKafkaDispatches() {
        try {
            Long n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM test_task_kafka_pending", Long.class);
            return n != null ? n : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    public void recordPendingKafkaDispatch(UUID taskId) {
        jdbcTemplate.update(
                "INSERT INTO test_task_kafka_pending(task_id) VALUES (?) ON CONFLICT (task_id) DO NOTHING",
                taskId);
        log.info("Task {} held for Kafka until queue pause is released (test_task_kafka_pending)", taskId);
    }

    public QueuePauseState setPaused(boolean paused) {
        jdbcTemplate.update(
                "UPDATE loadtest_system_settings SET queue_paused = ?, updated_at = now() WHERE id = 1",
                paused);
        log.info("Queue pause set to {}", paused);
        if (!paused) {
            drainPendingTestTaskKafkaEvents();
        }
        return getState();
    }

    public QueuePauseState getState() {
        return new QueuePauseState(isQueuePaused(), countPendingKafkaDispatches());
    }

    private void drainPendingTestTaskKafkaEvents() {
        while (true) {
            List<UUID> batch = jdbcTemplate.query(
                    "SELECT task_id FROM test_task_kafka_pending ORDER BY created_at LIMIT 100",
                    (rs, rowNum) -> rs.getObject("task_id", UUID.class));
            if (batch.isEmpty()) {
                break;
            }
            for (UUID taskUuid : batch) {
                String taskId = taskUuid.toString();
                try {
                    kafkaOutboxService.sendTestTaskEvent(taskId, TestTaskEvent.builder().taskId(taskId).build());
                    jdbcTemplate.update("DELETE FROM test_task_kafka_pending WHERE task_id = ?", taskUuid);
                    log.info("Released held Kafka event for task {} after queue unpause", taskId);
                } catch (Exception e) {
                    log.error("Failed to dispatch held Kafka event for task {}, will retry on next unpause", taskId, e);
                    return;
                }
            }
        }
    }

    public record QueuePauseState(boolean paused, long pendingKafkaDispatchCount) {}
}
