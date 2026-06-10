package com.loadtest.summarization.dto;

import java.io.Serializable;

public record SummarizationTaskEvent(String taskId, String summarizerName,
                                     String customPrompt, Boolean forceRetry) implements Serializable {

    public SummarizationTaskEvent(String taskId, String summarizerName) {
        this(taskId, summarizerName, null, null);
    }

    public SummarizationTaskEvent(String taskId, String summarizerName, String customPrompt) {
        this(taskId, summarizerName, customPrompt, null);
    }

    public boolean isForceRetry() {
        return Boolean.TRUE.equals(forceRetry);
    }
}
