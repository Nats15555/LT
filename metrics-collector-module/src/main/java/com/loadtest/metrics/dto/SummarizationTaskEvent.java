package com.loadtest.metrics.dto;

public record SummarizationTaskEvent(String taskId, String summarizerName, String customPrompt) {

    public SummarizationTaskEvent(String taskId, String summarizerName) {
        this(taskId, summarizerName, null);
    }
}
