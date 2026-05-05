package com.loadtest.execution.service;

import com.loadtest.execution.dto.TaskProcessOutcome;
import com.loadtest.execution.dto.TestTaskEvent;
import com.loadtest.execution.dto.TestTaskMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class TestTaskConsumer {

    private final TestTaskExecutionService testTaskExecutionService;
    private final MetricsTriggerService metricsTriggerService;

    @KafkaListener(topics = "${kafka.topic.test-tasks:test-tasks}", groupId = "${spring.kafka.consumer.group-id}")
    public void consumeTestTaskEvent(TestTaskEvent event, Acknowledgment acknowledgment) {
        log.info("Received test task event: taskId={}", event.getTaskId());
        try {
            TestTaskRunResult result = testTaskExecutionService.execute(event);
            if (result.getKind() == TestTaskRunResult.Kind.COMPLETED) {
                triggerMetricsCollectionAfterCompletedRun(result);
            }
        } catch (Exception e) {
            log.error("Unexpected error handling test task event taskId={}", event.getTaskId(), e);
        } finally {
            acknowledgment.acknowledge();
        }
    }

    private void triggerMetricsCollectionAfterCompletedRun(TestTaskRunResult result) {
        TaskProcessOutcome outcome = result.getOutcome();
        if (outcome == null) {
            return;
        }
        long start = outcome.testStartTimeMillis();
        long end = outcome.testEndTimeMillis();
        TestTaskMessage message = result.getMessage();
        boolean parsedRequests = message != null
                && message.getMetricsConfig() != null
                && message.getMetricsConfig().getRequests() != null
                && !message.getMetricsConfig().getRequests().isEmpty();
        if (result.hasNonEmptyMetricsRequests() || parsedRequests) {
            metricsTriggerService.triggerMetricsCollectionForTaskId(result.getTaskId().toString(), start, end);
            return;
        }
        log.info("No metrics collection Kafka event for taskId={} (no non-empty metrics_config.requests and no parsed requests)",
                result.getTaskId());
    }
}
