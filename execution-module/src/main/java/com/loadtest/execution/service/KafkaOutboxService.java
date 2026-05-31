package com.loadtest.execution.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadtest.execution.dto.MetricsCollectionEvent;
import com.loadtest.execution.persistence.KafkaOutboxEntity;
import com.loadtest.execution.persistence.KafkaOutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Service
@RequiredArgsConstructor
public class KafkaOutboxService {

    private static final String MODULE = "EXECUTION";
    private static final String EVENT_TYPE = "METRICS_COLLECTION_EVENT";
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_SENT = "SENT";
    private static final String MSG_INTERRUPTED = "Interrupted while publishing to Kafka";
    private static final String MSG_PUBLISH_FAILED = "Kafka publish failed";

    private final KafkaOutboxRepository kafkaOutboxRepository;
    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, MetricsCollectionEvent> kafkaTemplate;

    @Value("${kafka.topic.metrics-collection-tasks:metrics-collection-tasks}")
    private String metricsCollectionTasksTopic;
    @Value("${loadtest.kafka-outbox.retry-delay-ms:5000}")
    private long retryDelayMs;
    @Value("${loadtest.kafka-outbox.batch-size:50}")
    private int batchSize;

    @PostConstruct
    public void ensureSchema() {
        kafkaOutboxRepository.ensureTable();
        kafkaOutboxRepository.ensureRetryIndex();
    }

    public void sendMetricsCollectionEvent(String taskId, MetricsCollectionEvent event) {
        try {
            awaitSend(taskId, event);
        } catch (KafkaOutboxPublishException e) {
            storePending(taskId, event, e);
        }
    }

    private void awaitSend(String taskId, MetricsCollectionEvent event) {
        try {
            kafkaTemplate.send(metricsCollectionTasksTopic, taskId, event).get(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KafkaOutboxPublishException(MSG_INTERRUPTED, e);
        } catch (ExecutionException | TimeoutException e) {
            throw new KafkaOutboxPublishException(MSG_PUBLISH_FAILED, e);
        }
    }

    private void storePending(String taskId, MetricsCollectionEvent event, KafkaOutboxPublishException e) {
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException serEx) {
            payloadJson = "{\"error\":\"payload-serialization-failed\"}";
        }
        OffsetDateTime now = OffsetDateTime.now();
        kafkaOutboxRepository.save(KafkaOutboxEntity.builder()
                .id(UUID.randomUUID())
                .module(MODULE)
                .eventType(EVENT_TYPE)
                .topic(metricsCollectionTasksTopic)
                .eventKey(taskId)
                .payloadJson(payloadJson)
                .status(STATUS_PENDING)
                .attempts(1)
                .lastError(shrinkError(e))
                .nextAttemptAt(now.plus(retryDelayMs, ChronoUnit.MILLIS))
                .createdAt(now)
                .updatedAt(now)
                .build());
        log.warn("Kafka unavailable, outbox saved: module={}, eventType={}, key={}, reason={}",
                MODULE, EVENT_TYPE, taskId, e.getMessage());
    }

    @Scheduled(fixedDelayString = "${loadtest.kafka-outbox.retry-delay-ms:5000}")
    @Transactional
    public void flushPending() {
        List<KafkaOutboxEntity> rows = kafkaOutboxRepository
                .findByModuleAndStatusAndEventTypeAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                        MODULE, STATUS_PENDING, EVENT_TYPE, OffsetDateTime.now(), PageRequest.of(0, batchSize));
        for (KafkaOutboxEntity row : rows) {
            try {
                awaitSend(row.getEventKey(), readEvent(row.getPayloadJson()));
                row.setStatus(STATUS_SENT);
                row.setLastError(null);
                row.setUpdatedAt(OffsetDateTime.now());
            } catch (KafkaOutboxPublishException | IllegalStateException e) {
                row.setAttempts(row.getAttempts() + 1);
                row.setLastError(shrinkError(e));
                row.setNextAttemptAt(OffsetDateTime.now().plus(retryDelayMs, ChronoUnit.MILLIS));
                row.setUpdatedAt(OffsetDateTime.now());
            }
        }
    }

    private MetricsCollectionEvent readEvent(String payload) {
        try {
            return objectMapper.readValue(payload, MetricsCollectionEvent.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid outbox payload for METRICS_COLLECTION_EVENT", e);
        }
    }

    private static String shrinkError(Exception e) {
        String msg = e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "" : e.getMessage());
        return msg.length() > 2000 ? msg.substring(0, 2000) : msg;
    }
}
