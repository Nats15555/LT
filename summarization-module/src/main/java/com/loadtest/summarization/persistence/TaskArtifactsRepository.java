package com.loadtest.summarization.persistence;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@RequiredArgsConstructor
public class TaskArtifactsRepository {

    private final TestArtifactJpaRepository testArtifactJpaRepository;

    public List<ArtifactContent> findArtifactsByTaskId(UUID taskId) {
        List<ArtifactContent> result = new ArrayList<>();
        for (TestArtifactEntity entity : testArtifactJpaRepository.findByTaskIdOrderByFileName(taskId)) {
            byte[] content = entity.getFileContent();
            if (content != null) {
                String text = decodeContent(content, "gzip".equalsIgnoreCase(entity.getContentEncoding()));
                result.add(new ArtifactContent(entity.getFileName(), text));
            }
        }
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
                    return out.toString(StandardCharsets.UTF_8);
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
