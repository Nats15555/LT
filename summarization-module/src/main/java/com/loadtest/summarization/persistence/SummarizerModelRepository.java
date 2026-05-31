package com.loadtest.summarization.persistence;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Slf4j
@Repository
public class SummarizerModelRepository {

    private final SummarizerModelJpaRepository summarizerModelJpaRepository;
    private final String litellmBaseUrlOverride;

    public SummarizerModelRepository(
            SummarizerModelJpaRepository summarizerModelJpaRepository,
            @Value("${loadtest.summarization.litellm-base-url-override:}") String litellmBaseUrlOverride) {
        this.summarizerModelJpaRepository = summarizerModelJpaRepository;
        this.litellmBaseUrlOverride = litellmBaseUrlOverride;
    }

    public Optional<SummarizerConfig> findByName(String name) {
        try {
            return summarizerModelJpaRepository.findByNameAndEnabledTrue(name).map(this::toConfig);
        } catch (RuntimeException e) {
            log.warn("Failed to load summarizer by name: {}", name, e);
            return Optional.empty();
        }
    }

    private SummarizerConfig toConfig(SummarizerModelEntity entity) {
        String apiKeyEnvVar = entity.getApiKeyEnvVar();
        String apiKeyResolved = null;
        if (apiKeyEnvVar != null && !apiKeyEnvVar.isBlank()) {
            apiKeyResolved = System.getenv(apiKeyEnvVar);
        }
        return SummarizerConfig.builder()
                .id(entity.getId())
                .name(entity.getName())
                .provider(entity.getProvider())
                .baseUrl(applyLitellmBaseUrlOverride(entity.getBaseUrl()))
                .modelId(entity.getModelId())
                .apiKeyEnvVar(apiKeyEnvVar)
                .apiKeyResolved(apiKeyResolved)
                .build();
    }

    private String applyLitellmBaseUrlOverride(String baseUrl) {
        if (litellmBaseUrlOverride == null || litellmBaseUrlOverride.isBlank() || baseUrl == null) {
            return baseUrl;
        }
        String u = baseUrl.trim();
        if ("http://localhost:4000".equals(u) || "http://127.0.0.1:4000".equals(u)) {
            return litellmBaseUrlOverride.trim().replaceFirst("/+$", "");
        }
        return baseUrl;
    }
}
