package com.loadtest.app.service;

import com.loadtest.app.dto.CreateSummarizerRequest;
import com.loadtest.app.dto.SummarizerModelDto;
import com.loadtest.app.dto.UpdateSummarizerRequest;
import com.loadtest.app.persistence.SummarizerModelEntity;
import com.loadtest.app.persistence.SummarizerModelRepository;
import com.loadtest.app.util.ApiMessages;
import com.loadtest.app.util.SummarizerProviders;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
@Slf4j
@Service
@RequiredArgsConstructor
public class SummarizerService {

    private final SummarizerModelRepository repository;

    @Transactional
    public SummarizerModelDto create(CreateSummarizerRequest request) {
        if (repository.existsByName(request.name())) {
            throw new IllegalArgumentException(ApiMessages.Summarizers.nameAlreadyExists(request.name()));
        }
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        String provider = normalizeProvider(request.provider());
        String modelId = trimToEmpty(request.modelId());
        if (SummarizerProviders.EXTERNAL.equals(provider)) {
            validateExternalIngestUrl(request.baseUrl());
            if (modelId.isEmpty()) {
                modelId = "external";
            }
        } else if (modelId.isEmpty()) {
            throw new IllegalArgumentException(ApiMessages.Summarizers.MODEL_ID_REQUIRED_OPENAI);
        }
        SummarizerModelEntity entity = SummarizerModelEntity.builder()
                .id(id)
                .name(request.name())
                .provider(provider)
                .baseUrl(request.baseUrl())
                .modelId(modelId)
                .apiKeyEnvVar(request.apiKeyEnvVar())
                .enabled(request.enabled())
                .createdAt(now)
                .updatedAt(now)
                .build();
        repository.save(entity);
        log.info("Created summarizer model: {} (id: {})", entity.getName(), id);
        return toDto(entity);
    }

    @Transactional(readOnly = true)
    public List<SummarizerModelDto> getAll() {
        return repository.findAll().stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public List<SummarizerModelDto> getEnabled() {
        return repository.findByEnabledTrue().stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public SummarizerModelDto getById(UUID id) {
        SummarizerModelEntity entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(ApiMessages.Summarizers.notFoundById(id)));
        return toDto(entity);
    }

    @Transactional(readOnly = true)
    public SummarizerModelDto getByName(String name) {
        SummarizerModelEntity entity = repository.findByName(name)
                .orElseThrow(() -> new IllegalArgumentException(ApiMessages.Summarizers.notFoundByName(name)));
        return toDto(entity);
    }

    @Transactional
    public SummarizerModelDto update(UUID id, UpdateSummarizerRequest request) {
        SummarizerModelEntity entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(ApiMessages.Summarizers.notFoundById(id)));
        if (request.provider() != null) entity.setProvider(normalizeProvider(request.provider()));
        if (request.baseUrl() != null) entity.setBaseUrl(request.baseUrl());
        if (request.modelId() != null) entity.setModelId(request.modelId());
        if (request.apiKeyEnvVar() != null) entity.setApiKeyEnvVar(request.apiKeyEnvVar());
        if (request.enabled() != null) entity.setEnabled(request.enabled());
        validatePersistedSummarizer(entity);
        entity.setUpdatedAt(OffsetDateTime.now());
        repository.save(entity);
        log.info("Updated summarizer model: {} (id: {})", entity.getName(), id);
        return toDto(entity);
    }

    @Transactional
    public void delete(UUID id) {
        if (!repository.existsById(id)) {
            throw new IllegalArgumentException(ApiMessages.Summarizers.notFoundById(id));
        }
        repository.deleteById(id);
        log.info("Deleted summarizer model (id: {})", id);
    }

    private SummarizerModelDto toDto(SummarizerModelEntity e) {
        return new SummarizerModelDto(
                e.getId(),
                e.getName(),
                e.getProvider(),
                e.getBaseUrl(),
                e.getModelId(),
                e.getApiKeyEnvVar(),
                e.getEnabled(),
                e.getCreatedAt(),
                e.getUpdatedAt());
    }

    private static String normalizeProvider(String provider) {
        if (provider == null || provider.isBlank()) {
            return SummarizerProviders.OPENAI;
        }
        String p = provider.trim().toUpperCase();
        if (SummarizerProviders.EXTERNAL.equals(p)) {
            return SummarizerProviders.EXTERNAL;
        }
        return SummarizerProviders.OPENAI;
    }

    private static String trimToEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    private static void validateExternalIngestUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException(ApiMessages.Upload.EXTERNAL_BASE_URL_REQUIRED);
        }
        String t = baseUrl.trim();
        if (!t.matches("(?i)https?://.*")) {
            throw new IllegalArgumentException(ApiMessages.Upload.EXTERNAL_BASE_URL_SCHEME);
        }
    }

    private void validatePersistedSummarizer(SummarizerModelEntity e) {
        String p = e.getProvider();
        if (SummarizerProviders.EXTERNAL.equalsIgnoreCase(p)) {
            validateExternalIngestUrl(e.getBaseUrl());
            if (e.getModelId() == null || e.getModelId().isBlank()) {
                e.setModelId("external");
            }
        } else if (e.getModelId() == null || e.getModelId().isBlank()) {
            throw new IllegalArgumentException(ApiMessages.Summarizers.MODEL_ID_REQUIRED_OPENAI);
        }
    }
}
