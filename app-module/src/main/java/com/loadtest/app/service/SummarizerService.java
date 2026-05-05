package com.loadtest.app.service;

import com.loadtest.app.dto.CreateSummarizerRequest;
import com.loadtest.app.dto.SummarizerModelDto;
import com.loadtest.app.dto.UpdateSummarizerRequest;
import com.loadtest.app.persistence.SummarizerModelEntity;
import com.loadtest.app.persistence.SummarizerModelRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SummarizerService {

    private final SummarizerModelRepository repository;

    @Transactional
    public SummarizerModelDto create(CreateSummarizerRequest request) {
        if (repository.existsByName(request.getName())) {
            throw new IllegalArgumentException("Summarizer with name '" + request.getName() + "' already exists");
        }
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        String provider = normalizeProvider(request.getProvider());
        String modelId = trimToEmpty(request.getModelId());
        if ("EXTERNAL".equals(provider)) {
            validateExternalIngestUrl(request.getBaseUrl());
            if (modelId.isEmpty()) {
                modelId = "external";
            }
        } else if (modelId.isEmpty()) {
            throw new IllegalArgumentException("Model ID is required for OPENAI provider");
        }
        SummarizerModelEntity entity = SummarizerModelEntity.builder()
                .id(id)
                .name(request.getName())
                .provider(provider)
                .baseUrl(request.getBaseUrl())
                .modelId(modelId)
                .apiKeyEnvVar(request.getApiKeyEnvVar())
                .enabled(request.getEnabled())
                .createdAt(now)
                .updatedAt(now)
                .build();
        repository.save(entity);
        log.info("Created summarizer model: {} (id: {})", entity.getName(), id);
        return toDto(entity);
    }

    @Transactional(readOnly = true)
    public List<SummarizerModelDto> getAll() {
        return repository.findAll().stream().map(this::toDto).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<SummarizerModelDto> getEnabled() {
        return repository.findByEnabledTrue().stream().map(this::toDto).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public SummarizerModelDto getById(UUID id) {
        SummarizerModelEntity entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Summarizer with id '" + id + "' not found"));
        return toDto(entity);
    }

    @Transactional(readOnly = true)
    public SummarizerModelDto getByName(String name) {
        SummarizerModelEntity entity = repository.findByName(name)
                .orElseThrow(() -> new IllegalArgumentException("Summarizer with name '" + name + "' not found"));
        return toDto(entity);
    }

    @Transactional
    public SummarizerModelDto update(UUID id, UpdateSummarizerRequest request) {
        SummarizerModelEntity entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Summarizer with id '" + id + "' not found"));
        if (request.getProvider() != null) entity.setProvider(normalizeProvider(request.getProvider()));
        if (request.getBaseUrl() != null) entity.setBaseUrl(request.getBaseUrl());
        if (request.getModelId() != null) entity.setModelId(request.getModelId());
        if (request.getApiKeyEnvVar() != null) entity.setApiKeyEnvVar(request.getApiKeyEnvVar());
        if (request.getEnabled() != null) entity.setEnabled(request.getEnabled());
        validatePersistedSummarizer(entity);
        entity.setUpdatedAt(OffsetDateTime.now());
        repository.save(entity);
        log.info("Updated summarizer model: {} (id: {})", entity.getName(), id);
        return toDto(entity);
    }

    @Transactional
    public void delete(UUID id) {
        if (!repository.existsById(id)) {
            throw new IllegalArgumentException("Summarizer with id '" + id + "' not found");
        }
        repository.deleteById(id);
        log.info("Deleted summarizer model (id: {})", id);
    }

    private SummarizerModelDto toDto(SummarizerModelEntity e) {
        return SummarizerModelDto.builder()
                .id(e.getId())
                .name(e.getName())
                .provider(e.getProvider())
                .baseUrl(e.getBaseUrl())
                .modelId(e.getModelId())
                .apiKeyEnvVar(e.getApiKeyEnvVar())
                .enabled(e.getEnabled())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }

    private static String normalizeProvider(String provider) {
        if (provider == null || provider.isBlank()) {
            return "OPENAI";
        }
        String p = provider.trim().toUpperCase();
        if ("EXTERNAL".equals(p)) {
            return "EXTERNAL";
        }
        return "OPENAI";
    }

    private static String trimToEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    private static void validateExternalIngestUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("Полный URL приёма пакета (baseUrl) обязателен для провайдера EXTERNAL");
        }
        String t = baseUrl.trim();
        if (!t.matches("(?i)https?://.*")) {
            throw new IllegalArgumentException("baseUrl для EXTERNAL должен начинаться с http:// или https://");
        }
    }

    private void validatePersistedSummarizer(SummarizerModelEntity e) {
        String p = e.getProvider();
        if ("EXTERNAL".equalsIgnoreCase(p)) {
            validateExternalIngestUrl(e.getBaseUrl());
            if (e.getModelId() == null || e.getModelId().isBlank()) {
                e.setModelId("external");
            }
        } else if (e.getModelId() == null || e.getModelId().isBlank()) {
            throw new IllegalArgumentException("Model ID is required for OPENAI provider");
        }
    }
}
