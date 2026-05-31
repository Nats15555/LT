package com.loadtest.app.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SummarizerModelDto(
        UUID id,
        String name,
        String provider,
        @JsonProperty("baseUrl") String baseUrl,
        String modelId,
        @JsonProperty("apiKeyEnvVar") String apiKeyEnvVar,
        Boolean enabled,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
