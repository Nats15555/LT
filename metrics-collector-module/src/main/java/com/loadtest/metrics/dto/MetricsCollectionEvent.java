package com.loadtest.metrics.dto;

import java.io.Serializable;

public record MetricsCollectionEvent(String taskId, Long testStartTime, Long testEndTime) implements Serializable {
}
