package com.loadtest.execution.service;

import com.loadtest.execution.dto.TaskProcessOutcome;
import com.loadtest.execution.dto.TestTaskMessage;
import lombok.Getter;

import java.util.UUID;

public final class TestTaskRunResult {

    public enum Kind {
        DUPLICATE,
        COMPLETED,
        FAILED
    }

    @Getter
    private final Kind kind;
    @Getter
    private final UUID taskId;
    @Getter
    private final TestTaskMessage message;
    @Getter
    private final TaskProcessOutcome outcome;
    private final boolean hasNonEmptyMetricsRequests;

    private TestTaskRunResult(Kind kind, UUID taskId, TestTaskMessage message, TaskProcessOutcome outcome,
                              boolean hasNonEmptyMetricsRequests) {
        this.kind = kind;
        this.taskId = taskId;
        this.message = message;
        this.outcome = outcome;
        this.hasNonEmptyMetricsRequests = hasNonEmptyMetricsRequests;
    }

    public static TestTaskRunResult duplicate(UUID taskId) {
        return new TestTaskRunResult(Kind.DUPLICATE, taskId, null, null, false);
    }

    public static TestTaskRunResult completed(TestTaskMessage message, TaskProcessOutcome outcome,
                                              boolean hasNonEmptyMetricsRequests) {
        return new TestTaskRunResult(Kind.COMPLETED, UUID.fromString(message.taskId()), message, outcome,
                hasNonEmptyMetricsRequests);
    }

    public static TestTaskRunResult failed(UUID taskId) {
        return new TestTaskRunResult(Kind.FAILED, taskId, null, null, false);
    }

    public boolean hasNonEmptyMetricsRequests() {
        return hasNonEmptyMetricsRequests;
    }
}
