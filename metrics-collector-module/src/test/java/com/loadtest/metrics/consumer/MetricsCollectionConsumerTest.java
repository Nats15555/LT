package com.loadtest.metrics.consumer;

import com.loadtest.metrics.dto.MetricsCollectionEvent;
import com.loadtest.metrics.dto.MetricsCollectionRequest;
import com.loadtest.metrics.dto.MetricsCollectionResponse;
import com.loadtest.metrics.persistence.TestMetricsWriter;
import com.loadtest.metrics.service.MetricsCollectionRequestBuilder;
import com.loadtest.metrics.service.MetricsCollectionService;
import com.loadtest.metrics.service.PostMetricsPipelineService;
import com.loadtest.metrics.service.TaskHistoryLifecycleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MetricsCollectionConsumerTest {

    @Mock
    private MetricsCollectionRequestBuilder requestBuilder;
    @Mock
    private MetricsCollectionService metricsCollectionService;
    @Mock
    private TestMetricsWriter testMetricsWriter;
    @Mock
    private PostMetricsPipelineService postMetricsPipelineService;
    @Mock
    private TaskHistoryLifecycleService taskHistoryLifecycleService;
    @Mock
    private Acknowledgment acknowledgment;

    private MetricsCollectionConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new MetricsCollectionConsumer(
                requestBuilder,
                metricsCollectionService,
                testMetricsWriter,
                postMetricsPipelineService,
                taskHistoryLifecycleService);
    }

    @Test
    void consume_noRequest_finishesPipelineAndAck() {
        String taskId = "00000000-0000-0000-0000-000000000001";
        MetricsCollectionEvent event = new MetricsCollectionEvent(taskId, 1L, 2L);
        when(requestBuilder.tryBuildFromEvent(event)).thenReturn(Optional.empty());

        consumer.consume(event, acknowledgment);

        verify(postMetricsPipelineService).finishMetricsPhase(taskId, null, false);
        verify(acknowledgment).acknowledge();
    }

    @Test
    void consume_successPath_savesFinishesPipelineAndAck() {
        String taskId = "00000000-0000-0000-0000-000000000002";
        MetricsCollectionEvent event = new MetricsCollectionEvent(taskId, 1L, 2L);
        MetricsCollectionRequest req = new MetricsCollectionRequest(
                taskId,
                List.of(new MetricsCollectionRequest.MetricsRequestItem("n", "GET", "http://u", null, null, null)),
                0, null, null);
        MetricsCollectionResponse resp = new MetricsCollectionResponse(
                taskId, "SUCCESS", "ok", Map.of("n", Map.of("v", 1)), null, null, null);
        when(requestBuilder.tryBuildFromEvent(event)).thenReturn(Optional.of(req));
        when(metricsCollectionService.collectMetrics(req)).thenReturn(resp);
        when(testMetricsWriter.saveMetrics(taskId, req, resp)).thenReturn(1);

        consumer.consume(event, acknowledgment);

        verify(testMetricsWriter).saveMetrics(taskId, req, resp);
        verify(taskHistoryLifecycleService).markMetricsCollecting(any());
        verify(postMetricsPipelineService).finishMetricsPhase(taskId, resp, true);
        verify(acknowledgment).acknowledge();
    }

    @Test
    void consume_whenCollectorThrows_marksFailedAndAck() {
        String taskId = "00000000-0000-0000-0000-000000000003";
        MetricsCollectionEvent event = new MetricsCollectionEvent(taskId, 1L, 2L);
        MetricsCollectionRequest req = new MetricsCollectionRequest(taskId, List.of(), 0, null, null);
        when(requestBuilder.tryBuildFromEvent(event)).thenReturn(Optional.of(req));
        when(metricsCollectionService.collectMetrics(any())).thenThrow(new RuntimeException("boom"));

        consumer.consume(event, acknowledgment);

        verify(postMetricsPipelineService).failMetricsPhase(eq(taskId), eq("boom"));
        verify(acknowledgment).acknowledge();
    }

    @Test
    void consume_whenSavedRowsZero_stillFinishesPipelineAndAck() {
        String taskId = "00000000-0000-0000-0000-000000000004";
        MetricsCollectionEvent event = new MetricsCollectionEvent(taskId, 1L, 2L);
        MetricsCollectionRequest req = new MetricsCollectionRequest(
                taskId,
                List.of(new MetricsCollectionRequest.MetricsRequestItem("n", "GET", "http://u", null, null, null)),
                0, null, null);
        MetricsCollectionResponse resp = new MetricsCollectionResponse(
                taskId, "SUCCESS", "ok", Map.of("n", Map.of("v", 1)), null, null, null);
        when(requestBuilder.tryBuildFromEvent(event)).thenReturn(Optional.of(req));
        when(metricsCollectionService.collectMetrics(req)).thenReturn(resp);
        when(testMetricsWriter.saveMetrics(eq(taskId), eq(req), eq(resp))).thenReturn(0);

        consumer.consume(event, acknowledgment);

        verify(postMetricsPipelineService).finishMetricsPhase(taskId, resp, true);
        verify(acknowledgment).acknowledge();
    }
}
