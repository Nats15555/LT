package com.loadtest.app.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record UpdateSummarizerRequest(
        @JsonProperty("provider") String provider,
        @JsonProperty("baseUrl") String baseUrl,
        @JsonProperty("modelId") String modelId,
        @JsonProperty("apiKeyEnvVar") String apiKeyEnvVar,
        @JsonProperty("enabled") Boolean enabled) {
}
