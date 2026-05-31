package com.loadtest.execution.service;

import com.loadtest.execution.dto.MetricsCollectionEvent;
import com.loadtest.execution.dto.TestTaskMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class MetricsTriggerService {

    private final KafkaOutboxService kafkaOutboxService;

    public void triggerMetricsCollectionForTaskId(String taskId, long testStartTime, long testEndTime) {
        MetricsCollectionEvent event = new MetricsCollectionEvent(taskId, testStartTime, testEndTime);
        try {
            kafkaOutboxService.sendMetricsCollectionEvent(taskId, event);
            log.info("Metrics collection event queued for Kafka/outbox taskId: {} (testStart={}, testEnd={})",
                    taskId, testStartTime, testEndTime);
        } catch (RuntimeException e) {
            log.error("Failed to queue metrics collection event for taskId: {}", taskId, e);
        }
    }

    public void triggerMetricsCollection(TestTaskMessage taskMessage, Long testStartTime, Long testEndTime) {
        if (taskMessage.metricsConfig() == null
                || taskMessage.metricsConfig().requests() == null
                || taskMessage.metricsConfig().requests().isEmpty()) {
            log.info("No metrics collection configured for taskId: {}, skipping", taskMessage.taskId());
            return;
        }
        triggerMetricsCollectionForTaskId(taskMessage.taskId(), testStartTime, testEndTime);
    }
}
