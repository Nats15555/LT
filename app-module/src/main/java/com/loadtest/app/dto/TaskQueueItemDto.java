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
public class TaskQueueItemDto {
    @JsonProperty("id")
    private UUID taskId;
    private String status;
    private String testTool;
    private String testFileName;
    private String summarizerName;
    private java.util.UUID dockerExecutionProfileId;
    private String dockerProfileName;
    private OffsetDateTime createdAt;
}
