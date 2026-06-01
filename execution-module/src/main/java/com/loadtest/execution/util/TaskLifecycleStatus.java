package com.loadtest.execution.util;

public final class TaskLifecycleStatus {

    public static final String PENDING = "PENDING";
    public static final String PROCESSING = "PROCESSING";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";

    private TaskLifecycleStatus() {
    }

    public static boolean isTerminal(String status) {
        return COMPLETED.equals(status) || FAILED.equals(status);
    }
}
