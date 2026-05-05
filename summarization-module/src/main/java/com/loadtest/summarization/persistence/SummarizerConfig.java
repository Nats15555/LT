package com.loadtest.summarization.persistence;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SummarizerConfig {
    private UUID id;
    private String name;
    private String provider;
    private String baseUrl;
    private String modelId;
    private String apiKeyEnvVar;
    private String apiKeyResolved;
}
