package com.loadtest.app.service;

import com.loadtest.app.dto.CreateLoadTestToolRequest;
import com.loadtest.app.dto.LoadTestToolDto;
import com.loadtest.app.dto.UpdateLoadTestToolRequest;
import com.loadtest.app.persistence.LoadTestToolEntity;
import com.loadtest.app.persistence.LoadTestToolRepository;
import jakarta.persistence.EntityManager;
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
public class LoadTestToolService {

    private final LoadTestToolRepository toolRepository;
    private final EntityManager entityManager;

    @Transactional
    public LoadTestToolDto createTool(CreateLoadTestToolRequest request) {
        if (toolRepository.existsByName(request.getName())) {
            throw new IllegalArgumentException("Tool with name '" + request.getName() + "' already exists");
        }

        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        String name = request.getName().toUpperCase();
        Boolean enabled = request.getEnabled();

        String fileExtensionsStr;
        if (request.getFileExtensions() != null && !request.getFileExtensions().isEmpty()) {
            StringBuilder sb = new StringBuilder("{");
            for (int i = 0; i < request.getFileExtensions().size(); i++) {
                if (i > 0) sb.append(",");
                String ext = request.getFileExtensions().get(i)
                        .replace("\\", "\\\\")
                        .replace("\"", "\\\"");
                sb.append("\"").append(ext).append("\"");
            }
            sb.append("}");
            fileExtensionsStr = sb.toString();
        } else {
            fileExtensionsStr = "{}";
        }

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
                .setParameter("dockerImage", request.getDockerImage())
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
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<LoadTestToolDto> getEnabledTools() {
        return toolRepository.findByEnabledTrue().stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public LoadTestToolDto getToolById(UUID id) {
        LoadTestToolEntity entity = toolRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Tool with id '" + id + "' not found"));
        return toDto(entity);
    }

    @Transactional(readOnly = true)
    public LoadTestToolDto getToolByName(String name) {
        LoadTestToolEntity entity = toolRepository.findByName(name.toUpperCase())
                .orElseThrow(() -> new IllegalArgumentException("Tool with name '" + name + "' not found"));
        return toDto(entity);
    }

    @Transactional
    public LoadTestToolDto updateTool(UUID id, UpdateLoadTestToolRequest request) {
        if (!toolRepository.existsById(id)) {
            throw new IllegalArgumentException("Tool with id '" + id + "' not found");
        }

        StringBuilder sqlBuilder = new StringBuilder("UPDATE load_test_tools SET updated_at = :updatedAt");
        boolean hasUpdates = false;

        if (request.getDockerImage() != null) {
            sqlBuilder.append(", docker_image = :dockerImage");
            hasUpdates = true;
        }
        if (request.getFileExtensions() != null) {
            sqlBuilder.append(", file_extensions = CAST(:fileExtensions AS text[])");
            hasUpdates = true;
        }
        if (request.getEnabled() != null) {
            sqlBuilder.append(", enabled = :enabled");
            hasUpdates = true;
        }

        if (hasUpdates) {
            sqlBuilder.append(" WHERE id = :id");

            var query = entityManager.createNativeQuery(sqlBuilder.toString())
                    .setParameter("id", id)
                    .setParameter("updatedAt", OffsetDateTime.now());

            if (request.getDockerImage() != null) {
                query.setParameter("dockerImage", request.getDockerImage());
            }
            if (request.getFileExtensions() != null) {
                StringBuilder sb = new StringBuilder("{");
                for (int i = 0; i < request.getFileExtensions().size(); i++) {
                    if (i > 0) sb.append(",");
                    String ext = request.getFileExtensions().get(i)
                            .replace("\\", "\\\\")
                            .replace("\"", "\\\"");
                    sb.append("\"").append(ext).append("\"");
                }
                sb.append("}");
                query.setParameter("fileExtensions", sb.toString());
            }
            if (request.getEnabled() != null) {
                query.setParameter("enabled", request.getEnabled());
            }

            query.executeUpdate();
        }

        LoadTestToolEntity updated = toolRepository.findById(id)
                .orElseThrow(() -> new IllegalStateException("Failed to update tool: " + id));
        
        log.info("Updated load test tool: {} (id: {})", updated.getName(), updated.getId());
        
        return toDto(updated);
    }

    @Transactional
    public void deleteTool(UUID id) {
        if (!toolRepository.existsById(id)) {
            throw new IllegalArgumentException("Tool with id '" + id + "' not found");
        }
        toolRepository.deleteById(id);
        log.info("Deleted load test tool (id: {})", id);
    }

    private LoadTestToolDto toDto(LoadTestToolEntity entity) {
        return LoadTestToolDto.builder()
                .id(entity.getId())
                .name(entity.getName())
                .dockerImage(entity.getDockerImage())
                .fileExtensions(entity.getFileExtensions())
                .enabled(entity.getEnabled())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
