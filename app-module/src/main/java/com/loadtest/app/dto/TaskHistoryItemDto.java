package com.loadtest.app.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskHistoryItemDto {
    private UUID id;
    private String finalStatus;
    private String testTool;
    private String testFileName;
    private String summarizerName;
    @JsonProperty("command")
    private String command;
    private OffsetDateTime createdAt;
    private OffsetDateTime startedAt;
    private OffsetDateTime finishedAt;
    private String errorMessage;
    @JsonProperty("metricsCollected")
    private Boolean metricsCollected;
    private String fileContent;
    @JsonProperty("metricsConfig")
    private String metricsConfig;
    private String dockerProfileName;
}
