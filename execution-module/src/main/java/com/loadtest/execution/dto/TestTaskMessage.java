package com.loadtest.execution.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestTaskMessage implements Serializable {
    private String taskId;
    private String testTool;
    private String testFileName;
    private String testFileContent;

    private String command;
    private Integer expectedDurationSeconds;

    private String status;
    private Long timestamp;

    private MetricsConfig metricsConfig;

    private String dockerExecutionProfileId;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MetricsConfig implements Serializable {
        private Integer delaySeconds = 0;
        private java.util.List<MetricsRequest> requests;

        @Data
        @NoArgsConstructor
        @AllArgsConstructor
        public static class MetricsRequest implements Serializable {
            private String name;
            private String method = "GET";
            private String url;
            private java.util.Map<String, String> headers;
            private Object queryParams;
            private Object body;
        }
    }
}


