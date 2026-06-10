package com.loadtest.execution.service;

import com.loadtest.execution.util.DatabaseAvailabilityService;
import com.loadtest.execution.util.DatabaseUnavailableException;
import com.loadtest.execution.dto.ExecutionResponse;
import com.loadtest.execution.dto.TaskProcessOutcome;
import com.loadtest.execution.dto.TestTaskEvent;
import com.loadtest.execution.dto.TestTaskMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TestTaskConsumerTest {

    @Mock private DatabaseAvailabilityService databaseAvailabilityService;
    @Mock private TestTaskExecutionService testTaskExecutionService;
    @Mock private MetricsTriggerService metricsTriggerService;
    @Mock private Acknowledgment acknowledgment;

    private TestTaskConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new TestTaskConsumer(databaseAvailabilityService, testTaskExecutionService, metricsTriggerService);
    }

    @Test
    void databaseUnavailable_doesNotAcknowledge() {
        UUID taskId = UUID.randomUUID();
        doThrow(new DatabaseUnavailableException("down")).when(databaseAvailabilityService).requireAvailable();

        consumer.consumeTestTaskEvent(new TestTaskEvent(taskId.toString()), acknowledgment);

        verify(testTaskExecutionService, never()).execute(any());
        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    void completed_run_triggersMetricsWhenRequestsPresent() {
        UUID taskId = UUID.randomUUID();
        TestTaskMessage.MetricsConfig cfg = new TestTaskMessage.MetricsConfig(
                0, List.of(new TestTaskMessage.MetricsConfig.MetricsRequest("p", "GET", "http://u", null, null, null)));
        TestTaskMessage msg = new TestTaskMessage(
                taskId.toString(), null, null, null, null, null, null, null, cfg, null);
        ExecutionResponse resp = new ExecutionResponse("success", null, null, null, null, 1L, null, null);
        TaskProcessOutcome outcome = new TaskProcessOutcome(resp, 10L, 20L);
        when(testTaskExecutionService.execute(any()))
                .thenReturn(TestTaskRunResult.completed(msg, outcome, false, false));

        consumer.consumeTestTaskEvent(new TestTaskEvent(taskId.toString()), acknowledgment);

        verify(metricsTriggerService).triggerMetricsCollectionForTaskId(eq(taskId.toString()), eq(10L), eq(20L));
        verify(acknowledgment).acknowledge();
    }

    @Test
    void completed_triggersMetricsWhenHasNonEmptyMetricsRequestsFlagEvenIfMessageHasNoRequests() {
        UUID taskId = UUID.randomUUID();
        TestTaskMessage msg = new TestTaskMessage(taskId.toString(), null, null, null, null, null, null, null, null, null);
        ExecutionResponse resp = new ExecutionResponse("success", null, null, null, null, 1L, null, null);
        TaskProcessOutcome outcome = new TaskProcessOutcome(resp, 5L, 6L);
        when(testTaskExecutionService.execute(any()))
                .thenReturn(TestTaskRunResult.completed(msg, outcome, true, false));

        consumer.consumeTestTaskEvent(new TestTaskEvent(taskId.toString()), acknowledgment);

        verify(metricsTriggerService).triggerMetricsCollectionForTaskId(eq(taskId.toString()), eq(5L), eq(6L));
        verify(acknowledgment).acknowledge();
    }

    @Test
    void completed_noMetricsWhenNoRequestsAndFlagFalse() {
        UUID taskId = UUID.randomUUID();
        TestTaskMessage msg = new TestTaskMessage(taskId.toString(), null, null, null, null, null, null, null, null, null);
        ExecutionResponse resp = new ExecutionResponse("success", null, null, null, null, 1L, null, null);
        TaskProcessOutcome outcome = new TaskProcessOutcome(resp, 1L, 2L);
        when(testTaskExecutionService.execute(any()))
                .thenReturn(TestTaskRunResult.completed(msg, outcome, false, false));

        consumer.consumeTestTaskEvent(new TestTaskEvent(taskId.toString()), acknowledgment);

        verify(metricsTriggerService, never()).triggerMetricsCollectionForTaskId(any(), anyLong(), anyLong());
        verify(acknowledgment).acknowledge();
    }

    @Test
    void completed_nullMessage_shortCircuitsParsedRequests_noMetricsWhenFlagFalse() {
        UUID taskId = UUID.randomUUID();
        ExecutionResponse resp = new ExecutionResponse("success", null, null, null, null, 1L, null, null);
        TaskProcessOutcome outcome = new TaskProcessOutcome(resp, 1L, 2L);
        TestTaskRunResult result = mock(TestTaskRunResult.class);
        when(result.getKind()).thenReturn(TestTaskRunResult.Kind.COMPLETED);
        when(result.getOutcome()).thenReturn(outcome);
        when(result.getMessage()).thenReturn(null);
        when(result.getTaskId()).thenReturn(taskId);
        when(testTaskExecutionService.execute(any())).thenReturn(result);

        consumer.consumeTestTaskEvent(new TestTaskEvent(taskId.toString()), acknowledgment);

        verify(metricsTriggerService, never()).triggerMetricsCollectionForTaskId(any(), anyLong(), anyLong());
        verify(acknowledgment).acknowledge();
    }

    @Test
    void completed_metricsConfigPresent_requestsNull_noMetricsWhenFlagFalse() {
        UUID taskId = UUID.randomUUID();
        TestTaskMessage.MetricsConfig cfg = new TestTaskMessage.MetricsConfig(1, null);
        TestTaskMessage msg = new TestTaskMessage(
                taskId.toString(), null, null, null, null, null, null, null, cfg, null);
        ExecutionResponse resp = new ExecutionResponse("success", null, null, null, null, 1L, null, null);
        TaskProcessOutcome outcome = new TaskProcessOutcome(resp, 3L, 4L);
        when(testTaskExecutionService.execute(any()))
                .thenReturn(TestTaskRunResult.completed(msg, outcome, false, false));

        consumer.consumeTestTaskEvent(new TestTaskEvent(taskId.toString()), acknowledgment);

        verify(metricsTriggerService, never()).triggerMetricsCollectionForTaskId(any(), anyLong(), anyLong());
        verify(acknowledgment).acknowledge();
    }

    @Test
    void duplicate_doesNotTriggerMetrics() {
        UUID taskId = UUID.randomUUID();
        when(testTaskExecutionService.execute(any())).thenReturn(TestTaskRunResult.duplicate(taskId));

        consumer.consumeTestTaskEvent(new TestTaskEvent(taskId.toString()), acknowledgment);

        verify(metricsTriggerService, never()).triggerMetricsCollectionForTaskId(any(), anyLong(), anyLong());
        verify(acknowledgment).acknowledge();
    }

    @Test
    void failed_run_doesNotTriggerMetrics() {
        UUID taskId = UUID.randomUUID();
        when(testTaskExecutionService.execute(any())).thenReturn(TestTaskRunResult.failed(taskId));

        consumer.consumeTestTaskEvent(new TestTaskEvent(taskId.toString()), acknowledgment);

        verify(metricsTriggerService, never()).triggerMetricsCollectionForTaskId(any(), anyLong(), anyLong());
        verify(acknowledgment).acknowledge();
    }

    @Test
    void completed_withoutOutcome_skipsMetricsTrigger() {
        UUID taskId = UUID.randomUUID();
        TestTaskRunResult result = mock(TestTaskRunResult.class);
        when(result.getKind()).thenReturn(TestTaskRunResult.Kind.COMPLETED);
        when(result.getOutcome()).thenReturn(null);
        when(testTaskExecutionService.execute(any())).thenReturn(result);

        consumer.consumeTestTaskEvent(new TestTaskEvent(taskId.toString()), acknowledgment);

        verify(metricsTriggerService, never()).triggerMetricsCollectionForTaskId(any(), anyLong(), anyLong());
        verify(acknowledgment).acknowledge();
    }

    @Test
    void executeException_stillAcknowledges() {
        UUID taskId = UUID.randomUUID();
        when(testTaskExecutionService.execute(any())).thenThrow(new RuntimeException("boom"));

        consumer.consumeTestTaskEvent(new TestTaskEvent(taskId.toString()), acknowledgment);

        verify(metricsTriggerService, never()).triggerMetricsCollectionForTaskId(any(), anyLong(), anyLong());
        verify(acknowledgment).acknowledge();
    }
}
