package com.loadtest.app.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SummaryItemDto(
        UUID id,
        UUID taskId,
        String summaryType,
        Object summaryData,
        String processingStatus,
        String errorMessage,
        OffsetDateTime processedAt) {
}
