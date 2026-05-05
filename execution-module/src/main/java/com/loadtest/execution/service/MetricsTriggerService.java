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
        MetricsCollectionEvent event = MetricsCollectionEvent.builder()
                .taskId(taskId)
                .testStartTime(testStartTime)
                .testEndTime(testEndTime)
                .build();
        try {
            kafkaOutboxService.sendMetricsCollectionEvent(taskId, event);
            log.info("Metrics collection event queued for Kafka/outbox taskId: {} (testStart={}, testEnd={})",
                    taskId, testStartTime, testEndTime);
        } catch (Exception e) {
            log.error("Failed to queue metrics collection event for taskId: {}", taskId, e);
        }
    }

    public void triggerMetricsCollection(TestTaskMessage taskMessage, Long testStartTime, Long testEndTime) {
        if (taskMessage.getMetricsConfig() == null
                || taskMessage.getMetricsConfig().getRequests() == null
                || taskMessage.getMetricsConfig().getRequests().isEmpty()) {
            log.info("No metrics collection configured for taskId: {}, skipping", taskMessage.getTaskId());
            return;
        }
        triggerMetricsCollectionForTaskId(taskMessage.getTaskId(), testStartTime, testEndTime);
    }
}
