package com.loadtest.metrics.consumer;

import com.loadtest.metrics.dto.MetricsCollectionEvent;
import com.loadtest.metrics.dto.MetricsCollectionRequest;
import com.loadtest.metrics.dto.MetricsCollectionResponse;
import com.loadtest.metrics.persistence.TestMetricsWriter;
import com.loadtest.metrics.service.MetricsCollectionRequestBuilder;
import com.loadtest.metrics.service.MetricsCollectionService;
import com.loadtest.metrics.service.SummarizationEnqueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class MetricsCollectionConsumer {

    private final MetricsCollectionRequestBuilder requestBuilder;
    private final MetricsCollectionService metricsCollectionService;
    private final TestMetricsWriter testMetricsWriter;
    private final SummarizationEnqueueService summarizationEnqueueService;

    @KafkaListener(
            topics = "${kafka.topic.metrics-collection-tasks:metrics-collection-tasks}",
            groupId = "${spring.kafka.consumer.group-id:metrics-collector-group}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(MetricsCollectionEvent event, Acknowledgment acknowledgment) {
        log.info("Received metrics collection event: taskId={}, testStart={}, testEnd={}",
                event.getTaskId(), event.getTestStartTime(), event.getTestEndTime());

        try {
            Optional<MetricsCollectionRequest> requestOpt = requestBuilder.tryBuildFromEvent(event);
            if (requestOpt.isEmpty()) {
                summarizationEnqueueService.enqueueAfterMetricsSaved(event.getTaskId());
                acknowledgment.acknowledge();
                return;
            }
            MetricsCollectionRequest request = requestOpt.get();
            MetricsCollectionResponse response = metricsCollectionService.collectMetrics(request);
            int savedRows = testMetricsWriter.saveMetrics(event.getTaskId(), request, response);
            if (savedRows == 0) {
                log.warn("No rows written to test_metrics for taskId={} (check HTTP URLs, host-overrides, Prometheus/ES); summarization may still be enqueued if summarizer is set",
                        event.getTaskId());
            }
            summarizationEnqueueService.enqueueAfterMetricsSaved(event.getTaskId());
            acknowledgment.acknowledge();
        } catch (Exception e) {
            log.error("Error processing metrics collection for taskId: {}", event.getTaskId(), e);
            summarizationEnqueueService.enqueueAfterMetricsSaved(event.getTaskId());
            acknowledgment.acknowledge();
        }
    }
}
