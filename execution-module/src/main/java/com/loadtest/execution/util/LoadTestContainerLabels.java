package com.loadtest.execution.util;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class LoadTestContainerLabels {

    public static final String MANAGED = "loadtest.managed";
    public static final String TASK_ID = "loadtest.task-id";
    public static final String MANAGED_VALUE = "true";

    private LoadTestContainerLabels() {
    }

    public static Map<String, String> forTask(UUID taskId) {
        Map<String, String> labels = new HashMap<>();
        labels.put(MANAGED, MANAGED_VALUE);
        if (taskId != null) {
            labels.put(TASK_ID, taskId.toString());
        }
        return Map.copyOf(labels);
    }
}
