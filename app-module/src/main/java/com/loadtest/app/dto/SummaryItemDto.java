package com.loadtest.app.dto;

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
public class SummaryItemDto {
    private UUID id;
    private UUID taskId;
    private String summaryType;
    private Object summaryData;
    private String processingStatus;
    private String errorMessage;
    private OffsetDateTime processedAt;
}
