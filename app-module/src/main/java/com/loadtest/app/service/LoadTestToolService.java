package com.loadtest.app.service;

import com.loadtest.app.dto.CreateLoadTestToolRequest;
import com.loadtest.app.dto.LoadTestToolDto;
import com.loadtest.app.dto.UpdateLoadTestToolRequest;
import com.loadtest.app.persistence.LoadTestToolEntity;
import com.loadtest.app.persistence.LoadTestToolRepository;
import com.loadtest.app.util.ApiMessages;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
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
public class LoadTestToolService {

    private final LoadTestToolRepository toolRepository;
    private final EntityManager entityManager;

    @Transactional
    public LoadTestToolDto createTool(CreateLoadTestToolRequest request) {
        if (toolRepository.existsByName(request.name())) {
            throw new IllegalArgumentException(ApiMessages.Tools.nameAlreadyExists(request.name()));
        }

        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        String name = request.name().toUpperCase();
        Boolean enabled = request.enabled();

        String fileExtensionsStr = formatFileExtensionsLiteral(request.fileExtensions());

        String sql = """
            INSERT INTO load_test_tools (
                id, name, docker_image,
                file_extensions, enabled, created_at, updated_at
            ) VALUES (
                :id, :name, :dockerImage,
                CAST(:fileExtensionsStr AS text[]),
                :enabled, :createdAt, :updatedAt
            )
            """;

        entityManager.createNativeQuery(sql)
                .setParameter("id", id)
                .setParameter("name", name)
                .setParameter("dockerImage", request.dockerImage())
                .setParameter("fileExtensionsStr", fileExtensionsStr)
                .setParameter("enabled", enabled)
                .setParameter("createdAt", now)
                .setParameter("updatedAt", now)
                .executeUpdate();

        log.info("Created load test tool: {} (id: {})", name, id);

        LoadTestToolEntity saved = toolRepository.findById(id)
                .orElseThrow(() -> new IllegalStateException("Failed to create tool: " + name));
        
        return toDto(saved);
    }

    @Transactional(readOnly = true)
    public List<LoadTestToolDto> getAllTools() {
        return toolRepository.findAll().stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<LoadTestToolDto> getEnabledTools() {
        return toolRepository.findByEnabledTrue().stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public LoadTestToolDto getToolById(UUID id) {
        LoadTestToolEntity entity = toolRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(ApiMessages.Tools.notFoundById(id)));
        return toDto(entity);
    }

    @Transactional(readOnly = true)
    public LoadTestToolDto getToolByName(String name) {
        LoadTestToolEntity entity = toolRepository.findByName(name.toUpperCase())
                .orElseThrow(() -> new IllegalArgumentException(ApiMessages.Tools.notFoundByName(name)));
        return toDto(entity);
    }

    @Transactional
    public LoadTestToolDto updateTool(UUID id, UpdateLoadTestToolRequest request) {
        if (!toolRepository.existsById(id)) {
            throw new IllegalArgumentException(ApiMessages.Tools.notFoundById(id));
        }
        if (hasAnyToolUpdate(request)) {
            executeToolUpdate(id, request);
        }

        LoadTestToolEntity updated = toolRepository.findById(id)
                .orElseThrow(() -> new IllegalStateException("Failed to update tool: " + id));

        log.info("Updated load test tool: {} (id: {})", updated.getName(), updated.getId());

        return toDto(updated);
    }

    private static boolean hasAnyToolUpdate(UpdateLoadTestToolRequest request) {
        return request.dockerImage() != null
                || request.fileExtensions() != null
                || request.enabled() != null;
    }

    private void executeToolUpdate(UUID id, UpdateLoadTestToolRequest request) {
        StringBuilder sql = new StringBuilder("UPDATE load_test_tools SET updated_at = :updatedAt");
        appendToolUpdateColumns(sql, request);
        sql.append(" WHERE id = :id");

        Query query = entityManager.createNativeQuery(sql.toString())
                .setParameter("id", id)
                .setParameter("updatedAt", OffsetDateTime.now());
        bindToolUpdateParameters(query, request);
        query.executeUpdate();
    }

    private static void appendToolUpdateColumns(StringBuilder sql, UpdateLoadTestToolRequest request) {
        if (request.dockerImage() != null) {
            sql.append(", docker_image = :dockerImage");
        }
        if (request.fileExtensions() != null) {
            sql.append(", file_extensions = CAST(:fileExtensions AS text[])");
        }
        if (request.enabled() != null) {
            sql.append(", enabled = :enabled");
        }
    }

    private void bindToolUpdateParameters(Query query, UpdateLoadTestToolRequest request) {
        if (request.dockerImage() != null) {
            query.setParameter("dockerImage", request.dockerImage());
        }
        if (request.fileExtensions() != null) {
            query.setParameter("fileExtensions", formatFileExtensionsLiteral(request.fileExtensions()));
        }
        if (request.enabled() != null) {
            query.setParameter("enabled", request.enabled());
        }
    }

    private static String formatFileExtensionsLiteral(List<String> extensions) {
        if (extensions == null || extensions.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < extensions.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            String ext = extensions.get(i)
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"");
            sb.append("\"").append(ext).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    @Transactional
    public void deleteTool(UUID id) {
        if (!toolRepository.existsById(id)) {
            throw new IllegalArgumentException(ApiMessages.Tools.notFoundById(id));
        }
        toolRepository.deleteById(id);
        log.info("Deleted load test tool (id: {})", id);
    }

    private LoadTestToolDto toDto(LoadTestToolEntity entity) {
        return new LoadTestToolDto(
                entity.getId(),
                entity.getName(),
                entity.getDockerImage(),
                entity.getFileExtensions(),
                entity.getEnabled(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
