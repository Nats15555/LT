package com.loadtest.execution.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TaskProcessOutcomeTest {

    @Test
    void recordHoldsValues() {
        ExecutionResponse resp = new ExecutionResponse("COMPLETED", null, null, null, null, null, null, null);
        TaskProcessOutcome o = new TaskProcessOutcome(resp, 100L, 200L);
        assertThat(o.testStartTimeMillis()).isEqualTo(100L);
        assertThat(o.testEndTimeMillis()).isEqualTo(200L);
        assertThat(o.executionResponse()).isSameAs(resp);
    }
}
