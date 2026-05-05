package com.loadtest.execution.service;

import com.loadtest.execution.dto.MetricsCollectionEvent;
import com.loadtest.execution.dto.TestTaskMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class MetricsTriggerServiceTest {

    @Mock private KafkaOutboxService kafkaOutboxService;

    @InjectMocks private MetricsTriggerService metricsTriggerService;

    @Test
    void triggerForTaskId_delegatesToOutbox() {
        metricsTriggerService.triggerMetricsCollectionForTaskId("tid-1", 100L, 200L);
        verify(kafkaOutboxService).sendMetricsCollectionEvent(eq("tid-1"), any(MetricsCollectionEvent.class));
    }

    @Test
    void triggerForTaskId_swallowsOutboxException() {
        doThrow(new RuntimeException("kafka-down")).when(kafkaOutboxService)
                .sendMetricsCollectionEvent(any(), any(MetricsCollectionEvent.class));
        metricsTriggerService.triggerMetricsCollectionForTaskId("tid-2", 1L, 2L);
        verify(kafkaOutboxService).sendMetricsCollectionEvent(eq("tid-2"), any(MetricsCollectionEvent.class));
    }

    @Test
    void triggerFromMessage_skipsWhenMetricsConfigNull() {
        TestTaskMessage msg = TestTaskMessage.builder().taskId("t-null-cfg").build();
        metricsTriggerService.triggerMetricsCollection(msg, 1L, 2L);
        verify(kafkaOutboxService, never()).sendMetricsCollectionEvent(any(), any());
    }

    @Test
    void triggerFromMessage_skipsWhenRequestsNull() {
        TestTaskMessage.MetricsConfig cfg = new TestTaskMessage.MetricsConfig();
        cfg.setRequests(null);
        TestTaskMessage msg = TestTaskMessage.builder().taskId("t-req-null").metricsConfig(cfg).build();
        metricsTriggerService.triggerMetricsCollection(msg, 1L, 2L);
        verify(kafkaOutboxService, never()).sendMetricsCollectionEvent(any(), any());
    }

    @Test
    void triggerFromMessage_skipsWhenRequestsEmpty() {
        TestTaskMessage.MetricsConfig cfg = new TestTaskMessage.MetricsConfig();
        cfg.setRequests(List.of());
        TestTaskMessage msg = TestTaskMessage.builder().taskId("t-empty-req").metricsConfig(cfg).build();
        metricsTriggerService.triggerMetricsCollection(msg, 1L, 2L);
        verify(kafkaOutboxService, never()).sendMetricsCollectionEvent(any(), any());
    }

    @Test
    void triggerFromMessage_skipsWhenNoRequests() {
        TestTaskMessage msg = TestTaskMessage.builder().taskId("t").metricsConfig(new TestTaskMessage.MetricsConfig()).build();
        metricsTriggerService.triggerMetricsCollection(msg, 1L, 2L);
        verify(kafkaOutboxService, never()).sendMetricsCollectionEvent(any(), any());
    }

    @Test
    void triggerFromMessage_sendsWhenRequestsPresent() {
        TestTaskMessage.MetricsConfig cfg = new TestTaskMessage.MetricsConfig();
        cfg.setRequests(List.of(new TestTaskMessage.MetricsConfig.MetricsRequest("x", "GET", "http://h", null, null, null)));
        TestTaskMessage msg = TestTaskMessage.builder().taskId("t3").metricsConfig(cfg).build();
        metricsTriggerService.triggerMetricsCollection(msg, 5L, 6L);
        verify(kafkaOutboxService).sendMetricsCollectionEvent(eq("t3"), any(MetricsCollectionEvent.class));
    }
}
