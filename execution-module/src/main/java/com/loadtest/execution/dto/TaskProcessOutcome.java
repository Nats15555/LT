package com.loadtest.execution.dto;

public record TaskProcessOutcome(ExecutionResponse executionResponse, long testStartTimeMillis, long testEndTimeMillis) {
}
