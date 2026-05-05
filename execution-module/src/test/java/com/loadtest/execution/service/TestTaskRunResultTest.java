package com.loadtest.execution.service;

import com.loadtest.execution.dto.ExecutionResponse;
import com.loadtest.execution.dto.TaskProcessOutcome;
import com.loadtest.execution.dto.TestTaskMessage;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TestTaskRunResultTest {

    @Test
    void duplicate_exposesTaskId() {
        UUID id = UUID.randomUUID();
        TestTaskRunResult r = TestTaskRunResult.duplicate(id);
        assertThat(r.getKind()).isEqualTo(TestTaskRunResult.Kind.DUPLICATE);
        assertThat(r.getTaskId()).isEqualTo(id);
        assertThat(r.getMessage()).isNull();
        assertThat(r.getOutcome()).isNull();
        assertThat(r.hasNonEmptyMetricsRequests()).isFalse();
    }

    @Test
    void failed_exposesTaskId() {
        UUID id = UUID.randomUUID();
        TestTaskRunResult r = TestTaskRunResult.failed(id);
        assertThat(r.getKind()).isEqualTo(TestTaskRunResult.Kind.FAILED);
        assertThat(r.getTaskId()).isEqualTo(id);
        assertThat(r.hasNonEmptyMetricsRequests()).isFalse();
    }

    @Test
    void completed_exposesMessageOutcomeAndMetricsFlag() {
        TestTaskMessage msg = TestTaskMessage.builder().taskId(UUID.randomUUID().toString()).build();
        TaskProcessOutcome out = new TaskProcessOutcome(
                ExecutionResponse.builder().status("ok").executionTime(1L).build(),
                10L,
                20L);
        TestTaskRunResult r = TestTaskRunResult.completed(msg, out, true);
        assertThat(r.getKind()).isEqualTo(TestTaskRunResult.Kind.COMPLETED);
        assertThat(r.getMessage()).isSameAs(msg);
        assertThat(r.getOutcome()).isSameAs(out);
        assertThat(r.hasNonEmptyMetricsRequests()).isTrue();

        TestTaskRunResult r2 = TestTaskRunResult.completed(msg, out, false);
        assertThat(r2.hasNonEmptyMetricsRequests()).isFalse();
    }
}
