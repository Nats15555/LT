package com.loadtest.app.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadtest.app.dto.SummarizationTaskEvent;
import com.loadtest.app.dto.TestTaskEvent;
import com.loadtest.app.persistence.KafkaOutboxEntity;
import com.loadtest.app.persistence.KafkaOutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
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

    private static final String MODULE = "APP";
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_SENT = "SENT";
    private static final String EVENT_TYPE_TEST_TASK = "TEST_TASK_EVENT";
    private static final String EVENT_TYPE_SUMMARIZATION_TASK = "SUMMARIZATION_TASK_EVENT";
    private static final String MSG_INTERRUPTED = "Interrupted while publishing to Kafka";
    private static final String MSG_PUBLISH_FAILED = "Kafka publish failed";

    private final KafkaOutboxRepository kafkaOutboxRepository;
    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, TestTaskEvent> testTaskKafkaTemplate;
    @Qualifier("summarizationKafkaTemplate")
    private final KafkaTemplate<String, SummarizationTaskEvent> summarizationKafkaTemplate;

    @Value("${kafka.topic.test-tasks:test-tasks}")
    private String testTasksTopic;
    @Value("${kafka.topic.summarization-tasks:summarization-tasks}")
    private String summarizationTasksTopic;
    @Value("${loadtest.kafka-outbox.retry-delay-ms:5000}")
    private long retryDelayMs;
    @Value("${loadtest.kafka-outbox.batch-size:50}")
    private int batchSize;

    @PostConstruct
    public void ensureSchema() {
        kafkaOutboxRepository.ensureTable();
        kafkaOutboxRepository.ensureRetryIndex();
    }

    public void sendTestTaskEvent(String taskId, TestTaskEvent event) {
        sendOrStore(EVENT_TYPE_TEST_TASK, testTasksTopic, taskId, event);
    }

    public void sendSummarizationTaskEvent(String taskId, SummarizationTaskEvent event) {
        sendOrStore(EVENT_TYPE_SUMMARIZATION_TASK, summarizationTasksTopic, taskId, event);
    }

    private void sendOrStore(String eventType, String topic, String key, Object payload) {
        try {
            publishNow(topic, key, payload, eventType);
        } catch (KafkaOutboxPublishException e) {
            storePending(eventType, topic, key, payload, e);
        }
    }

    private void publishNow(String topic, String key, Object payload, String eventType) {
        try {
            if (EVENT_TYPE_TEST_TASK.equals(eventType)) {
                testTaskKafkaTemplate.send(topic, key, (TestTaskEvent) payload).get(15, TimeUnit.SECONDS);
            } else {
                summarizationKafkaTemplate.send(topic, key, (SummarizationTaskEvent) payload).get(15, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KafkaOutboxPublishException(MSG_INTERRUPTED, e);
        } catch (ExecutionException | TimeoutException e) {
            throw new KafkaOutboxPublishException(MSG_PUBLISH_FAILED, e);
        }
    }

    private void storePending(String eventType, String topic, String key, Object payload, KafkaOutboxPublishException e) {
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException serEx) {
            payloadJson = "{\"error\":\"payload-serialization-failed\"}";
        }
        OffsetDateTime now = OffsetDateTime.now();
        kafkaOutboxRepository.save(KafkaOutboxEntity.builder()
                .id(UUID.randomUUID())
                .module(MODULE)
                .eventType(eventType)
                .topic(topic)
                .eventKey(key)
                .payloadJson(payloadJson)
                .status(STATUS_PENDING)
                .attempts(1)
                .lastError(shrinkError(e))
                .nextAttemptAt(now.plus(retryDelayMs, ChronoUnit.MILLIS))
                .createdAt(now)
                .updatedAt(now)
                .build());
        log.warn("Kafka unavailable, outbox saved: module={}, eventType={}, key={}, reason={}",
                MODULE, eventType, key, e.getMessage());
    }

    @Scheduled(fixedDelayString = "${loadtest.kafka-outbox.retry-delay-ms:5000}")
    @Transactional
    public void flushPending() {
        List<KafkaOutboxEntity> rows = kafkaOutboxRepository
                .findByModuleAndStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                        MODULE, STATUS_PENDING, OffsetDateTime.now(), PageRequest.of(0, batchSize));
        for (KafkaOutboxEntity row : rows) {
            try {
                if (EVENT_TYPE_TEST_TASK.equals(row.getEventType())) {
                    publishNow(row.getTopic(), row.getEventKey(), readTestTaskEvent(row.getPayloadJson()), row.getEventType());
                } else if (EVENT_TYPE_SUMMARIZATION_TASK.equals(row.getEventType())) {
                    publishNow(row.getTopic(), row.getEventKey(), readSummarizationTaskEvent(row.getPayloadJson()), row.getEventType());
                } else {
                    throw new IllegalStateException("Unknown outbox event type: " + row.getEventType());
                }
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

    private TestTaskEvent readTestTaskEvent(String payload) {
        try {
            return objectMapper.readValue(payload, TestTaskEvent.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid outbox payload for " + EVENT_TYPE_TEST_TASK, e);
        }
    }

    private SummarizationTaskEvent readSummarizationTaskEvent(String payload) {
        try {
            return objectMapper.readValue(payload, SummarizationTaskEvent.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid outbox payload for " + EVENT_TYPE_SUMMARIZATION_TASK, e);
        }
    }

    private static String shrinkError(Exception e) {
        String msg = e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "" : e.getMessage());
        return msg.length() > 2000 ? msg.substring(0, 2000) : msg;
    }
}
