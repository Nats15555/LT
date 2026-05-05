package com.loadtest.app.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
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
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SummarizerModelDto {
    private UUID id;
    private String name;
    private String provider;
    @JsonProperty("baseUrl")
    private String baseUrl;
    private String modelId;
    @JsonProperty("apiKeyEnvVar")
    private String apiKeyEnvVar;
    private Boolean enabled;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
