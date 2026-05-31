package com.loadtest.metrics.consumer;

import com.loadtest.metrics.dto.MetricsCollectionEvent;
import com.loadtest.metrics.dto.MetricsCollectionRequest;
import com.loadtest.metrics.dto.MetricsCollectionResponse;
import com.loadtest.metrics.persistence.TestMetricsWriter;
import com.loadtest.metrics.service.MetricsCollectionRequestBuilder;
import com.loadtest.metrics.service.MetricsCollectionService;
import com.loadtest.metrics.service.SummarizationEnqueueService;
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
    private SummarizationEnqueueService summarizationEnqueueService;
    @Mock
    private Acknowledgment acknowledgment;

    private MetricsCollectionConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new MetricsCollectionConsumer(
                requestBuilder,
                metricsCollectionService,
                testMetricsWriter,
                summarizationEnqueueService);
    }

    @Test
    void consume_noRequest_enqueuesAndAck() {
        MetricsCollectionEvent event = new MetricsCollectionEvent("t1", 1L, 2L);
        when(requestBuilder.tryBuildFromEvent(event)).thenReturn(Optional.empty());

        consumer.consume(event, acknowledgment);

        verify(summarizationEnqueueService).enqueueAfterMetricsSaved("t1");
        verify(acknowledgment).acknowledge();
    }

    @Test
    void consume_successPath_savesEnqueuesAndAck() {
        MetricsCollectionEvent event = new MetricsCollectionEvent("t2", 1L, 2L);
        MetricsCollectionRequest req = new MetricsCollectionRequest(
                "t2",
                List.of(new MetricsCollectionRequest.MetricsRequestItem("n", "GET", "http://u", null, null, null)),
                0, null, null);
        MetricsCollectionResponse resp = new MetricsCollectionResponse(
                "t2", "SUCCESS", "ok", Map.of("n", Map.of("v", 1)), null, null, null);
        when(requestBuilder.tryBuildFromEvent(event)).thenReturn(Optional.of(req));
        when(metricsCollectionService.collectMetrics(req)).thenReturn(resp);
        when(testMetricsWriter.saveMetrics("t2", req, resp)).thenReturn(1);

        consumer.consume(event, acknowledgment);

        verify(testMetricsWriter).saveMetrics("t2", req, resp);
        verify(summarizationEnqueueService).enqueueAfterMetricsSaved("t2");
        verify(acknowledgment).acknowledge();
    }

    @Test
    void consume_whenCollectorThrows_stillEnqueuesAndAck() {
        MetricsCollectionEvent event = new MetricsCollectionEvent("t3", 1L, 2L);
        MetricsCollectionRequest req = new MetricsCollectionRequest("t3", List.of(), 0, null, null);
        when(requestBuilder.tryBuildFromEvent(event)).thenReturn(Optional.of(req));
        when(metricsCollectionService.collectMetrics(any())).thenThrow(new RuntimeException("boom"));

        consumer.consume(event, acknowledgment);

        verify(summarizationEnqueueService).enqueueAfterMetricsSaved("t3");
        verify(acknowledgment).acknowledge();
    }

    @Test
    void consume_whenSavedRowsZero_stillEnqueuesAndAck() {
        MetricsCollectionEvent event = new MetricsCollectionEvent("t4", 1L, 2L);
        MetricsCollectionRequest req = new MetricsCollectionRequest(
                "t4",
                List.of(new MetricsCollectionRequest.MetricsRequestItem("n", "GET", "http://u", null, null, null)),
                0, null, null);
        MetricsCollectionResponse resp = new MetricsCollectionResponse(
                "t4", "SUCCESS", "ok", Map.of("n", Map.of("v", 1)), null, null, null);
        when(requestBuilder.tryBuildFromEvent(event)).thenReturn(Optional.of(req));
        when(metricsCollectionService.collectMetrics(req)).thenReturn(resp);
        when(testMetricsWriter.saveMetrics(eq("t4"), eq(req), eq(resp))).thenReturn(0);

        consumer.consume(event, acknowledgment);

        verify(summarizationEnqueueService).enqueueAfterMetricsSaved("t4");
        verify(acknowledgment).acknowledge();
    }
}

