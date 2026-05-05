package com.loadtest.metrics.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MetricsCollectionEvent implements Serializable {

    private String taskId;
    private Long testStartTime;
    private Long testEndTime;
}
