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
public class MetricsItemDto {
    private UUID id;
    private String sourceType;
    private String endpointUrl;
    private String queryParams;
    private Object metricsData;
    private OffsetDateTime collectedAt;
}
