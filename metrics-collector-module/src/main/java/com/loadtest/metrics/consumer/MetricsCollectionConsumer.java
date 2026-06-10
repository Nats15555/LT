package com.loadtest.metrics.consumer;

import com.loadtest.metrics.dto.MetricsCollectionEvent;
import com.loadtest.metrics.dto.MetricsCollectionRequest;
import com.loadtest.metrics.dto.MetricsCollectionResponse;
import com.loadtest.metrics.persistence.TestMetricsWriter;
import com.loadtest.metrics.service.MetricsCollectionRequestBuilder;
import com.loadtest.metrics.service.MetricsCollectionService;
import com.loadtest.metrics.service.PostMetricsPipelineService;
import com.loadtest.metrics.service.TaskHistoryLifecycleService;
import com.loadtest.metrics.util.DatabaseAvailabilityService;
import com.loadtest.metrics.util.DatabaseUnavailableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class MetricsCollectionConsumer {

    private final DatabaseAvailabilityService databaseAvailabilityService;
    private final MetricsCollectionRequestBuilder requestBuilder;
    private final MetricsCollectionService metricsCollectionService;
    private final TestMetricsWriter testMetricsWriter;
    private final PostMetricsPipelineService postMetricsPipelineService;
    private final TaskHistoryLifecycleService taskHistoryLifecycleService;

    @KafkaListener(
            topics = "${kafka.topic.metrics-collection-tasks:metrics-collection-tasks}",
            groupId = "${spring.kafka.consumer.group-id:metrics-collector-group}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(MetricsCollectionEvent event, Acknowledgment acknowledgment) {
        log.info("Received metrics collection event: taskId={}, testStart={}, testEnd={}",
                event.taskId(), event.testStartTime(), event.testEndTime());

        try {
            databaseAvailabilityService.requireAvailable();
            Optional<MetricsCollectionRequest> requestOpt = requestBuilder.tryBuildFromEvent(event);
            if (requestOpt.isEmpty()) {
                postMetricsPipelineService.finishMetricsPhase(event.taskId(), null, false);
                acknowledgment.acknowledge();
                return;
            }
            MetricsCollectionRequest request = requestOpt.get();
            taskHistoryLifecycleService.markMetricsCollecting(UUID.fromString(event.taskId()));
            MetricsCollectionResponse response = metricsCollectionService.collectMetrics(request);
            int savedRows = testMetricsWriter.saveMetrics(event.taskId(), request, response);
            if (savedRows == 0) {
                log.warn("No rows written to test_metrics for taskId={} (check HTTP URLs, host-overrides, Prometheus/ES); post-metrics pipeline will still run",
                        event.taskId());
            }
            postMetricsPipelineService.finishMetricsPhase(event.taskId(), response, true);
            acknowledgment.acknowledge();
        } catch (DatabaseUnavailableException e) {
            log.warn("PostgreSQL unavailable for taskId={}, message will be redelivered: {}",
                    event.taskId(), e.getMessage());
        } catch (RuntimeException e) {
            if (DatabaseAvailabilityService.isDatabaseAccessFailure(e)) {
                log.warn("Database error for taskId={}, message will be redelivered: {}",
                        event.taskId(), e.getMessage());
                return;
            }
            log.error("Error processing metrics collection for taskId: {}", event.taskId(), e);
            try {
                postMetricsPipelineService.failMetricsPhase(event.taskId(), e.getMessage());
                acknowledgment.acknowledge();
            } catch (RuntimeException failEx) {
                if (DatabaseAvailabilityService.isDatabaseAccessFailure(failEx)) {
                    log.warn("Could not persist FAILED status for taskId={}, message will be redelivered",
                            event.taskId());
                } else {
                    throw failEx;
                }
            }
        }
    }
}
