package com.loadtest.execution.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadtest.execution.dto.MetricsCollectionEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class KafkaOutboxService {

    private static final String MODULE = "EXECUTION";
    private static final String EVENT_TYPE = "METRICS_COLLECTION_EVENT";
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_SENT = "SENT";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, MetricsCollectionEvent> kafkaTemplate;

    @Value("${kafka.topic.metrics-collection-tasks:metrics-collection-tasks}")
    private String metricsCollectionTasksTopic;
    @Value("${loadtest.kafka-outbox.retry-delay-ms:5000}")
    private long retryDelayMs;
    @Value("${loadtest.kafka-outbox.batch-size:50}")
    private int batchSize;

    @PostConstruct
    public void ensureTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS kafka_outbox (
                    id UUID PRIMARY KEY,
                    module VARCHAR(32) NOT NULL,
                    event_type VARCHAR(64) NOT NULL,
                    topic VARCHAR(128) NOT NULL,
                    event_key VARCHAR(128) NOT NULL,
                    payload_json TEXT NOT NULL,
                    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
                    attempts INTEGER NOT NULL DEFAULT 0,
                    last_error TEXT,
                    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """);
        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_kafka_outbox_retry
                ON kafka_outbox(module, status, next_attempt_at, created_at)
                """);
    }

    public void sendMetricsCollectionEvent(String taskId, MetricsCollectionEvent event) {
        try {
            kafkaTemplate.send(metricsCollectionTasksTopic, taskId, event).get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            storePending(taskId, event, e);
        }
    }

    private void storePending(String taskId, MetricsCollectionEvent event, Exception e) {
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(event);
        } catch (Exception serEx) {
            payloadJson = "{\"error\":\"payload-serialization-failed\"}";
        }
        jdbcTemplate.update("""
                        INSERT INTO kafka_outbox(id, module, event_type, topic, event_key, payload_json, status, attempts, last_error, next_attempt_at, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?, now(), now())
                        """,
                UUID.randomUUID(), MODULE, EVENT_TYPE, metricsCollectionTasksTopic, taskId, payloadJson, STATUS_PENDING,
                shrinkError(e), Timestamp.from(Instant.now().plusMillis(retryDelayMs)));
        log.warn("Kafka unavailable, outbox saved: module={}, eventType={}, key={}, reason={}",
                MODULE, EVENT_TYPE, taskId, e.getMessage());
    }

    @Scheduled(fixedDelayString = "${loadtest.kafka-outbox.retry-delay-ms:5000}")
    public void flushPending() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                        SELECT id, event_key, payload_json
                        FROM kafka_outbox
                        WHERE module = ? AND status = ? AND event_type = ? AND next_attempt_at <= now()
                        ORDER BY created_at
                        LIMIT ?
                        """,
                MODULE, STATUS_PENDING, EVENT_TYPE, batchSize);
        for (Map<String, Object> row : rows) {
            UUID id = (UUID) row.get("id");
            String key = (String) row.get("event_key");
            try {
                String payload = (String) row.get("payload_json");
                MetricsCollectionEvent event = objectMapper.readValue(payload, MetricsCollectionEvent.class);
                kafkaTemplate.send(metricsCollectionTasksTopic, key, event).get(15, TimeUnit.SECONDS);
                jdbcTemplate.update("UPDATE kafka_outbox SET status = ?, last_error = NULL, updated_at = now() WHERE id = ?",
                        STATUS_SENT, id);
            } catch (Exception e) {
                jdbcTemplate.update("""
                                UPDATE kafka_outbox
                                SET attempts = attempts + 1,
                                    last_error = ?,
                                    next_attempt_at = ?,
                                    updated_at = now()
                                WHERE id = ?
                                """,
                        shrinkError(e), Timestamp.from(Instant.now().plusMillis(retryDelayMs)), id);
            }
        }
    }

    private static String shrinkError(Exception e) {
        String msg = e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "" : e.getMessage());
        return msg.length() > 2000 ? msg.substring(0, 2000) : msg;
    }
}

