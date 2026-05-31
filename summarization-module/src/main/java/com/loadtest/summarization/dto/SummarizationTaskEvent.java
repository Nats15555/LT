package com.loadtest.summarization.dto;

import java.io.Serializable;

public record SummarizationTaskEvent(String taskId, String summarizerName,
                                     String customPrompt) implements Serializable {
    public SummarizationTaskEvent(String taskId, String summarizerName) {
        this(taskId, summarizerName, null);
    }
}
