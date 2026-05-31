package com.loadtest.app.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateSummarizerRequest(
        @NotBlank(message = "Name is required")
        @JsonProperty("name") String name,
        @JsonProperty("provider") String provider,
        @JsonProperty("baseUrl") String baseUrl,
        @JsonProperty("modelId") String modelId,
        @JsonProperty("apiKeyEnvVar") String apiKeyEnvVar,
        @NotNull(message = "Enabled is required")
        @JsonProperty("enabled") Boolean enabled) {
}
