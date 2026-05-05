package com.loadtest.execution.service;

import com.loadtest.execution.persistence.TestArtifactEntity;
import com.loadtest.execution.persistence.TestArtifactRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class ArtifactCollectorService {

    private final TestArtifactRepository artifactRepository;
    private final CommandFromDbService commandFromDbService;

    public void collectAndSaveArtifacts(UUID taskId, String commandTemplate, Map<String, String> placeholders) {
        if (taskId == null || placeholders == null) return;
        if (commandTemplate == null || commandTemplate.isBlank()) {
            log.info("Артефакты не собирались (taskId={}): команда пуста", taskId);
            return;
        }
        boolean hasPlaceholders = commandTemplate.contains("{reportBaseName}") || commandTemplate.contains("{metricsBaseName}");
        if (!hasPlaceholders) {
            log.info("Артефакты не собирались (taskId={}): в команде нет {reportBaseName} или {metricsBaseName}", taskId);
            return;
        }

        List<String> fromCommand = commandFromDbService.deriveArtifactFilePathsFromCommand(commandTemplate, placeholders);
        String reportsHostPath = placeholders.getOrDefault("reportsHostPath", "").trim();
        String metricsHostPath = placeholders.getOrDefault("metricsHostPath", "").trim();
        String reportBase = placeholders.getOrDefault("reportBaseName", "");
        String metricsBase = placeholders.getOrDefault("metricsBaseName", reportBase);

        Set<String> pathSet = new LinkedHashSet<>();
        for (String p : fromCommand) {
            if (p != null && !p.isBlank()) pathSet.add(Paths.get(p).toAbsolutePath().normalize().toString());
        }
        addFilesByPrefix(pathSet, reportsHostPath, reportBase);
        addFilesByPrefix(pathSet, metricsHostPath, metricsBase);

        List<ArtifactFile> files = pathSet.stream().map(p -> new ArtifactFile(Paths.get(p))).toList();

        if (files.isEmpty()) {
            String msg = "В команде указаны {reportBaseName} или {metricsBaseName}, но пути к файлам не получены. Убедитесь, что в команде есть шаблоны вида {reportBaseName}.html или что в каталоге отчётов есть файлы с таким префиксом.";
            log.warn("{} (taskId={})", msg, taskId);
            throw new IllegalStateException(msg);
        }

        int saved = 0;
        int missing = 0;
        for (ArtifactFile af : files) {
            try {
                if (!Files.exists(af.path())) {
                    missing++;
                    log.info("Artifact not found (taskId={}): {}", taskId, af.path().toAbsolutePath());
                    continue;
                }
                byte[] original = Files.readAllBytes(af.path());
                byte[] gz = gzip(original);
                TestArtifactEntity entity = TestArtifactEntity.builder()
                        .id(UUID.randomUUID())
                        .taskId(taskId)
                        .fileName(af.path().getFileName().toString())
                        .contentEncoding("gzip")
                        .fileContent(gz)
                        .originalSizeBytes((long) original.length)
                        .compressedSizeBytes((long) gz.length)
                        .createdAt(OffsetDateTime.now())
                        .build();
                artifactRepository.save(entity);
                saved++;
                log.info("Saved artifact (taskId={}): {} orig={}B gz={}B",
                        taskId, entity.getFileName(), entity.getOriginalSizeBytes(), entity.getCompressedSizeBytes());
                try {
                    Files.delete(af.path());
                } catch (IOException e) {
                    log.warn("Could not delete local artifact file after save: {}", af.path().toAbsolutePath(), e);
                }
            } catch (Exception e) {
                log.warn("Failed to save artifact (taskId={}): {}", taskId, af.path().toAbsolutePath(), e);
            }
        }
        deleteRemainingFilesByPrefix(reportsHostPath, reportBase);
        deleteRemainingFilesByPrefix(metricsHostPath, metricsBase);
        log.info("Artifact collection finished (taskId={}): saved={} missing={}", taskId, saved, missing);
    }

    private static void deleteRemainingFilesByPrefix(String dirStr, String prefix) {
        if (dirStr == null || dirStr.isBlank() || prefix == null || prefix.isBlank()) return;
        Path dir = Paths.get(dirStr).toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> list = Files.list(dir)) {
            list.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith(prefix))
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                            log.debug("Deleted remaining artifact file: {}", p.getFileName());
                        } catch (IOException e) {
                            log.warn("Could not delete remaining file {}: {}", p, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.debug("Could not list directory for cleanup {}: {}", dir, e.getMessage());
        }
    }

    private static void addFilesByPrefix(Set<String> pathSet, String dirStr, String prefix) {
        if (dirStr == null || dirStr.isBlank() || prefix == null || prefix.isBlank()) return;
        Path dir = Paths.get(dirStr).toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> list = Files.list(dir)) {
            list.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith(prefix))
                    .forEach(p -> pathSet.add(p.toAbsolutePath().normalize().toString()));
        } catch (IOException e) {
            log.debug("Could not list directory {} for prefix {}: {}", dir, prefix, e.getMessage());
        }
    }

    private static byte[] gzip(byte[] input) throws IOException {
        if (input == null || input.length == 0) return new byte[0];
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gos = new GZIPOutputStream(baos)) {
            gos.write(input);
        }
        return baos.toByteArray();
    }

    private record ArtifactFile(Path path) {}
}
