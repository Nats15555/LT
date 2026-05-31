package com.loadtest.app.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.UUID;

public record TaskHistoryItemDto(
        UUID id,
        String finalStatus,
        String testTool,
        String testFileName,
        String summarizerName,
        @JsonProperty("command") String command,
        OffsetDateTime createdAt,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        String errorMessage,
        @JsonProperty("metricsCollected") Boolean metricsCollected,
        String fileContent,
        @JsonProperty("metricsConfig") String metricsConfig,
        String dockerProfileName) {
}
