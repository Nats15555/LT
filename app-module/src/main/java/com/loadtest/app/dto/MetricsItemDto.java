package com.loadtest.app.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record MetricsItemDto(
        UUID id,
        String sourceType,
        String endpointUrl,
        String queryParams,
        Object metricsData,
        OffsetDateTime collectedAt) {
}
