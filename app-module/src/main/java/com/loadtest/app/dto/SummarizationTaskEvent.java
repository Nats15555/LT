package com.loadtest.app.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SummarizationTaskEvent {
    private String taskId;
    private String summarizerName;
}
