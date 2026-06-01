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
        log.info("Received test task event: taskId={}", event.taskId());
        try {
            TestTaskRunResult result = testTaskExecutionService.execute(event);
            if (result.getKind() == TestTaskRunResult.Kind.COMPLETED) {
                triggerMetricsCollectionAfterCompletedRun(result);
            }
        } catch (RuntimeException e) {
            log.error("Unexpected error handling test task event taskId={}", event.taskId(), e);
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
                && message.metricsConfig() != null
                && message.metricsConfig().requests() != null
                && !message.metricsConfig().requests().isEmpty();
        boolean needsPipeline = result.needsPostExecutionPipeline() || parsedRequests;
        if (!needsPipeline) {
            log.info("No post-execution pipeline for taskId={} (terminal COMPLETED, no metrics or summarizer)",
                    result.getTaskId());
            return;
        }
        boolean triggerMetricsKafka = result.hasNonEmptyMetricsRequests() || parsedRequests
                || result.hasConfiguredSummarizer();
        if (triggerMetricsKafka) {
            metricsTriggerService.triggerMetricsCollectionForTaskId(result.getTaskId().toString(), start, end);
            return;
        }
        log.info("No metrics-collection Kafka event for taskId={} (unexpected: post-execution pipeline was required)",
                result.getTaskId());
    }
}
