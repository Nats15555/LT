package com.loadtest.summarization.persistence;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

@Slf4j
@Repository
public class TaskArtifactsRepository {

    private final JdbcTemplate jdbcTemplate;

    public TaskArtifactsRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<ArtifactContent> findArtifactsByTaskId(UUID taskId) {
        String sql = "SELECT id, file_name, content_encoding, file_content FROM test_artifacts WHERE task_id = ? ORDER BY file_name";
        List<ArtifactContent> result = new ArrayList<>();
        jdbcTemplate.query(sql, rs -> {
            String fileName = rs.getString("file_name");
            String encoding = rs.getString("content_encoding");
            byte[] content = rs.getBytes("file_content");
            if (content != null) {
                String text = decodeContent(content, "gzip".equalsIgnoreCase(encoding));
                result.add(new ArtifactContent(fileName, text));
            }
        }, taskId);
        return result;
    }

    private String decodeContent(byte[] content, boolean gzip) {
        try {
            if (gzip) {
                try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(content));
                     ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = gis.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                    return out.toString(StandardCharsets.UTF_8.name());
                }
            }
            return new String(content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to decode artifact content: {}", e.getMessage());
            return "[binary or unsupported encoding]";
        }
    }

    @Data
    public static class ArtifactContent {
        private final String fileName;
        private final String textContent;
    }
}
