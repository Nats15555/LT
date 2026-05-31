package com.loadtest.execution.dto;

import java.io.Serializable;

public record MetricsCollectionEvent(String taskId, Long testStartTime, Long testEndTime) implements Serializable {
}
