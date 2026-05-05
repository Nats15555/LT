package com.loadtest.summarization.persistence;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@Repository
public class SummarizerModelRepository {

    private final JdbcTemplate jdbcTemplate;

    private final String litellmBaseUrlOverride;

    public SummarizerModelRepository(
            JdbcTemplate jdbcTemplate,
            @Value("${loadtest.summarization.litellm-base-url-override:}") String litellmBaseUrlOverride) {
        this.jdbcTemplate = jdbcTemplate;
        this.litellmBaseUrlOverride = litellmBaseUrlOverride;
    }

    public Optional<SummarizerConfig> findByName(String name) {
        String sql = "SELECT id, name, COALESCE(provider, 'OPENAI') AS provider, base_url, model_id, api_key_env_var FROM summarizer_models WHERE name = ? AND enabled = true";
        try {
            return jdbcTemplate.query(sql, rs -> {
                if (rs.next()) {
                    String apiKeyEnvVar = rs.getString("api_key_env_var");
                    String apiKeyResolved = null;
                    if (apiKeyEnvVar != null && !apiKeyEnvVar.isBlank()) {
                        apiKeyResolved = System.getenv(apiKeyEnvVar);
                    }
                    String baseUrl = applyLitellmBaseUrlOverride(rs.getString("base_url"));
                    return Optional.of(SummarizerConfig.builder()
                            .id(UUID.fromString(rs.getString("id")))
                            .name(rs.getString("name"))
                            .provider(rs.getString("provider"))
                            .baseUrl(baseUrl)
                            .modelId(rs.getString("model_id"))
                            .apiKeyEnvVar(apiKeyEnvVar)
                            .apiKeyResolved(apiKeyResolved)
                            .build());
                }
                return Optional.empty();
            }, name);
        } catch (Exception e) {
            log.warn("Failed to load summarizer by name: {}", name, e);
            return Optional.empty();
        }
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
