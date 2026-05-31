package com.loadtest.app.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.UUID;

public record TaskQueueItemDto(
        @JsonProperty("id") UUID taskId,
        String status,
        String testTool,
        String testFileName,
        String summarizerName,
        UUID dockerExecutionProfileId,
        String dockerProfileName,
        OffsetDateTime createdAt) {
}
