package com.loadtest.app.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateSummarizerRequest {

    @JsonProperty("provider")
    private String provider;

    @JsonProperty("baseUrl")
    private String baseUrl;

    @JsonProperty("modelId")
    private String modelId;

    @JsonProperty("apiKeyEnvVar")
    private String apiKeyEnvVar;

    @JsonProperty("enabled")
    private Boolean enabled;
}
