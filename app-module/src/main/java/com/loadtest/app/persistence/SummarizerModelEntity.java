package com.loadtest.app.persistence;

import jakarta.persistence.*;
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
@Entity
@Table(name = "summarizer_models")
public class SummarizerModelEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "name", nullable = false, unique = true, length = 64)
    private String name;

    @Column(name = "provider", nullable = false, length = 32)
    private String provider;

    @Column(name = "base_url", columnDefinition = "TEXT")
    private String baseUrl;

    @Column(name = "model_id", nullable = false, length = 128)
    private String modelId;

    @Column(name = "api_key_env_var", length = 128)
    private String apiKeyEnvVar;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
