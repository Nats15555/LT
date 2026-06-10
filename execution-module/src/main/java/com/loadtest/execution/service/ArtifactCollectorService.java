package com.loadtest.execution.service;

import com.loadtest.execution.persistence.TestArtifactEntity;
import com.loadtest.execution.persistence.TestArtifactRepository;
import com.loadtest.execution.util.ExecutionPlaceholderKeys;
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
        if (shouldSkipArtifactCollection(taskId, commandTemplate, placeholders)) {
            return;
        }
        ArtifactHostPaths hostPaths = artifactHostPaths(placeholders);
        List<ArtifactFile> files = resolveArtifactFiles(commandTemplate, placeholders, hostPaths);
        if (files.isEmpty()) {
            failNoArtifactPaths(taskId);
        }
        ArtifactSaveCounts counts = persistArtifactFiles(taskId, files);
        deleteRemainingFilesByPrefix(hostPaths.reportsHostPath(), hostPaths.reportBase());
        deleteRemainingFilesByPrefix(hostPaths.metricsHostPath(), hostPaths.metricsBase());
        log.info("Artifact collection finished (taskId={}): saved={} missing={}",
                taskId, counts.saved(), counts.missing());
    }

    private boolean shouldSkipArtifactCollection(UUID taskId, String commandTemplate, Map<String, String> placeholders) {
        if (taskId == null || placeholders == null) {
            return true;
        }
        if (commandTemplate == null || commandTemplate.isBlank()) {
            log.info("Артефакты не собирались (taskId={}): команда пуста", taskId);
            return true;
        }
        if (!commandContainsArtifactPlaceholders(commandTemplate)) {
            log.info("Артефакты не собирались (taskId={}): в команде нет {reportBaseName} или {metricsBaseName}", taskId);
            return true;
        }
        return false;
    }

    private static boolean commandContainsArtifactPlaceholders(String commandTemplate) {
        return commandTemplate.contains("{reportBaseName}") || commandTemplate.contains("{metricsBaseName}");
    }

    private static ArtifactHostPaths artifactHostPaths(Map<String, String> placeholders) {
        String reportBase = placeholders.getOrDefault(ExecutionPlaceholderKeys.REPORT_BASE_NAME, "");
        return new ArtifactHostPaths(
                placeholders.getOrDefault(ExecutionPlaceholderKeys.REPORTS_HOST_PATH, "").trim(),
                placeholders.getOrDefault(ExecutionPlaceholderKeys.METRICS_HOST_PATH, "").trim(),
                reportBase,
                placeholders.getOrDefault(ExecutionPlaceholderKeys.METRICS_BASE_NAME, reportBase));
    }

    private List<ArtifactFile> resolveArtifactFiles(
            String commandTemplate,
            Map<String, String> placeholders,
            ArtifactHostPaths hostPaths) {
        List<String> fromCommand = commandFromDbService.deriveArtifactFilePathsFromCommand(commandTemplate, placeholders);
        Set<String> pathSet = new LinkedHashSet<>();
        for (String p : fromCommand) {
            if (p != null && !p.isBlank()) {
                pathSet.add(Paths.get(p).toAbsolutePath().normalize().toString());
            }
        }
        addFilesByPrefix(pathSet, hostPaths.reportsHostPath(), hostPaths.reportBase());
        addFilesByPrefix(pathSet, hostPaths.metricsHostPath(), hostPaths.metricsBase());
        return pathSet.stream().map(p -> new ArtifactFile(Paths.get(p))).toList();
    }

    private static void failNoArtifactPaths(UUID taskId) {
        String msg = "В команде указаны {reportBaseName} или {metricsBaseName}, но пути к файлам не получены. "
                     + "Убедитесь, что в команде есть шаблоны вида {reportBaseName}.html или что в каталоге отчётов есть файлы с таким префиксом.";
        log.warn("{} (taskId={})", msg, taskId);
        throw new IllegalStateException(msg);
    }

    private ArtifactSaveCounts persistArtifactFiles(UUID taskId, List<ArtifactFile> files) {
        int saved = 0;
        int missing = 0;
        for (ArtifactFile af : files) {
            try {
                if (!Files.exists(af.path())) {
                    missing++;
                    log.info("Artifact not found (taskId={}): {}", taskId, af.path().toAbsolutePath());
                    continue;
                }
                saveOneArtifact(taskId, af);
                saved++;
            } catch (IOException | RuntimeException e) {
                log.warn("Failed to save artifact (taskId={}): {}", taskId, af.path().toAbsolutePath(), e);
            }
        }
        return new ArtifactSaveCounts(saved, missing);
    }

    private void saveOneArtifact(UUID taskId, ArtifactFile af) throws IOException {
        String fileName = af.path().getFileName().toString();
        if (artifactRepository.existsByTaskIdAndFileName(taskId, fileName)) {
            log.info("Artifact already saved (taskId={}): {}, skipping duplicate", taskId, fileName);
            deleteLocalArtifactQuietly(af.path());
            return;
        }
        byte[] original = Files.readAllBytes(af.path());
        byte[] gz = gzip(original);
        TestArtifactEntity entity = TestArtifactEntity.builder()
                .id(UUID.randomUUID())
                .taskId(taskId)
                .fileName(fileName)
                .contentEncoding("gzip")
                .fileContent(gz)
                .originalSizeBytes((long) original.length)
                .compressedSizeBytes((long) gz.length)
                .createdAt(OffsetDateTime.now())
                .build();
        artifactRepository.save(entity);
        log.info("Saved artifact (taskId={}): {} orig={}B gz={}B",
                taskId, entity.getFileName(), entity.getOriginalSizeBytes(), entity.getCompressedSizeBytes());
        deleteLocalArtifactQuietly(af.path());
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

    private static void deleteLocalArtifactQuietly(Path path) {
        try {
            Files.delete(path);
        } catch (IOException e) {
            log.warn("Could not delete local artifact file after save: {}", path.toAbsolutePath(), e);
        }
    }

    private static byte[] gzip(byte[] input) throws IOException {
        if (input == null || input.length == 0) return new byte[0];
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        try (GZIPOutputStream gos = new GZIPOutputStream(byteArrayOutputStream)) {
            gos.write(input);
        }
        return byteArrayOutputStream.toByteArray();
    }

    private record ArtifactFile(Path path) {
    }

    private record ArtifactHostPaths(String reportsHostPath, String metricsHostPath, String reportBase,
                                     String metricsBase) {
    }

    private record ArtifactSaveCounts(int saved, int missing) {
    }
}
