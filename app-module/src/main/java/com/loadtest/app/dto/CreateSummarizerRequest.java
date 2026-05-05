package com.loadtest.app.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateSummarizerRequest {

    @NotBlank(message = "Name is required")
    @JsonProperty("name")
    private String name;

    @JsonProperty("provider")
    private String provider;

    @JsonProperty("baseUrl")
    private String baseUrl;

    @JsonProperty("modelId")
    private String modelId;

    @JsonProperty("apiKeyEnvVar")
    private String apiKeyEnvVar;

    @NotNull(message = "Enabled is required")
    @JsonProperty("enabled")
    private Boolean enabled;
}
