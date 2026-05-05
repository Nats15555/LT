package com.loadtest.execution.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;
import com.loadtest.execution.persistence.DockerExecutionProfileEntity;
import com.loadtest.execution.persistence.LoadTestToolEntity;
import com.loadtest.execution.persistence.LoadTestToolRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CommandFromDbService {

    private final LoadTestToolRepository toolRepository;
    private final ObjectMapper objectMapper;

    @Value("${file.storage.working-dir:}")
    private String workingDir;
    @Value("${file.storage.artifact-base-path:artifacts}")
    private String artifactBasePathFallback;
    @Value("${file.storage.artifact-reports-subdir:reports}")
    private String artifactReportsSubdirFallback;
    @Value("${file.storage.artifact-metrics-subdir:metrics}")
    private String artifactMetricsSubdirFallback;
    @Value("${container.default-network:}")
    private String defaultNetworkName;

    @Value("${loadtest.execution.named-volume-for-child-binds:}")
    private String namedVolumeForChildBinds;

    public record ArtifactPaths(String reportsPath, String metricsPath, String reportsSubdir, String metricsSubdir) {}

    public Optional<LoadTestToolEntity> getToolByName(String name) {
        return toolRepository.findByNameAndEnabledTrue(name);
    }

    public ArtifactPaths resolveArtifactPaths() {
        java.nio.file.Path base = (workingDir != null && !workingDir.isBlank())
                ? Paths.get(workingDir).toAbsolutePath().normalize()
                : Paths.get(".").toAbsolutePath().normalize();
        String reportsPath = base.resolve(artifactBasePathFallback).resolve(artifactReportsSubdirFallback).toString();
        String metricsPath = base.resolve(artifactBasePathFallback).resolve(artifactMetricsSubdirFallback).toString();
        return new ArtifactPaths(reportsPath, metricsPath, artifactReportsSubdirFallback, artifactMetricsSubdirFallback);
    }

    public List<String> buildCommand(String command, Map<String, String> placeholders) {
        if (command == null || command.isBlank()) {
            return new ArrayList<>();
        }
        Map<String, String> all = new LinkedHashMap<>(placeholders != null ? placeholders : Map.of());
        String line = substitute(command, all);
        return Arrays.stream(line.split("\\s+")).filter(s -> !s.isEmpty()).collect(Collectors.toList());
    }

    private static String substitute(String s, Map<String, String> placeholders) {
        if (placeholders == null || placeholders.isEmpty()) return s;
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            s = s.replace("{" + e.getKey() + "}", e.getValue() != null ? e.getValue() : "");
        }
        return s;
    }

    private static final Pattern REPORT_PLACEHOLDER = Pattern.compile("\\{reportBaseName\\}([^\\s/\\\\]*)");
    private static final Pattern METRICS_PLACEHOLDER = Pattern.compile("\\{metricsBaseName\\}([^\\s/\\\\]*)");

    public List<String> deriveArtifactFilePathsFromCommand(String commandTemplate, Map<String, String> placeholders) {
        List<String> hostPaths = new ArrayList<>();
        if (commandTemplate == null || commandTemplate.isBlank() || placeholders == null) return hostPaths;
        String reportsHostPath = placeholders.getOrDefault("reportsHostPath", "").replace("\\", "/").replaceAll("/+$", "");
        String metricsHostPath = placeholders.getOrDefault("metricsHostPath", "").replace("\\", "/").replaceAll("/+$", "");
        String reportBase = placeholders.getOrDefault("reportBaseName", "");
        String metricsBase = placeholders.getOrDefault("metricsBaseName", reportBase);
        for (Matcher m = REPORT_PLACEHOLDER.matcher(commandTemplate); m.find(); ) {
            String suffix = m.group(1);
            String fileName = reportBase + suffix;
            if (!fileName.isEmpty() && !reportsHostPath.isEmpty()) {
                hostPaths.add(reportsHostPath + "/" + fileName);
            }
        }
        for (Matcher m = METRICS_PLACEHOLDER.matcher(commandTemplate); m.find(); ) {
            String suffix = m.group(1);
            String fileName = metricsBase + suffix;
            if (!fileName.isEmpty() && !metricsHostPath.isEmpty()) {
                hostPaths.add(metricsHostPath + "/" + fileName);
            }
        }
        return hostPaths;
    }

    public java.util.Optional<String> namedVolumeForChildBinds() {
        if (namedVolumeForChildBinds == null || namedVolumeForChildBinds.isBlank()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(namedVolumeForChildBinds.trim());
    }

    public List<Bind> buildBinds(Map<String, String> placeholders) {
        return buildBindsUsingHostPaths(placeholders, java.util.Optional.empty());
    }

    public List<Bind> buildBindsUsingHostPaths(Map<String, String> placeholders, java.util.Optional<String> namedVolumeHostMountpoint) {
        List<Bind> binds = new ArrayList<>();
        if (placeholders == null) {
            return binds;
        }
        java.util.function.Function<String, String> hostPathOrSame = containerSidePath -> {
            if (containerSidePath == null || containerSidePath.isBlank()) {
                return null;
            }
            if (namedVolumeHostMountpoint.isEmpty()) {
                return containerSidePath;
            }
            return mapPathUnderWorkingDirToHostVolumeMountpoint(containerSidePath, namedVolumeHostMountpoint.get());
        };
        String testPath = hostPathOrSame.apply(placeholders.get("testFileHostPath"));
        String reportsPath = hostPathOrSame.apply(placeholders.get("reportsHostPath"));
        String metricsPath = hostPathOrSame.apply(placeholders.get("metricsHostPath"));
        if (hasBindableHostPath(testPath)) {
            binds.add(new Bind(testPath, new Volume("/mnt/test")));
        }
        if (hasBindableHostPath(reportsPath)) {
            binds.add(new Bind(reportsPath, new Volume("/mnt/reports")));
        }
        if (hasBindableHostPath(metricsPath)) {
            binds.add(new Bind(metricsPath, new Volume("/mnt/metrics")));
        }
        return binds;
    }

    private String mapPathUnderWorkingDirToHostVolumeMountpoint(String containerSideDir, String namedVolumeHostMountpoint) {
        if (workingDir == null || workingDir.isBlank()) {
            throw new IllegalStateException(
                    "file.storage.working-dir must be set when loadtest.execution.named-volume-for-child-binds is used");
        }
        Path wd = Paths.get(workingDir).toAbsolutePath().normalize();
        Path dir = Paths.get(containerSideDir).toAbsolutePath().normalize();
        final Path rel;
        try {
            rel = wd.relativize(dir);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "Path " + dir + " must be under file.storage.working-dir (" + wd + ") when resolving named volume binds",
                    e);
        }
        if (violatesWorkingDirContainment(rel)) {
            throw new IllegalStateException(
                    "Path " + dir + " must be under file.storage.working-dir (" + wd + ") when resolving named volume binds");
        }
        Path hostRoot = Paths.get(namedVolumeHostMountpoint).normalize();
        if (wd.equals(dir)) {
            return hostRoot.toString();
        }
        return hostRoot.resolve(rel.toString()).toString();
    }

    static boolean hasBindableHostPath(String path) {
        return path != null && !path.isBlank();
    }

    static boolean violatesWorkingDirContainment(Path relativized) {
        return relativized.isAbsolute() || relativized.toString().startsWith("..");
    }

    public HostConfig applyDockerProfile(HostConfig base, DockerExecutionProfileEntity config) {
        HostConfig h = base != null ? base : HostConfig.newHostConfig();

        if (config.getMemoryLimitMb() != null && config.getMemoryLimitMb() > 0) {
            h.withMemory((long) config.getMemoryLimitMb() * 1024 * 1024);
        }
        if (config.getMemoryReservationMb() != null && config.getMemoryReservationMb() > 0) {
            h.withMemoryReservation((long) config.getMemoryReservationMb() * 1024 * 1024);
        }
        if (config.getCpuLimit() != null && config.getCpuLimit().doubleValue() > 0) {
            try {
                h.withCpuQuota((long) (config.getCpuLimit().doubleValue() * 100_000));
                h.withCpuPeriod(100_000L);
            } catch (Exception e) {
                log.debug("CPU limit not applied: {}", e.getMessage());
            }
        }
        if (config.getCpuShares() != null && config.getCpuShares() > 0) {
            h.withCpuShares(config.getCpuShares());
        }
        String network = (config.getNetworkMode() != null && !config.getNetworkMode().isBlank())
                ? config.getNetworkMode()
                : (defaultNetworkName != null && !defaultNetworkName.isBlank() ? defaultNetworkName : null);
        if (network != null) {
            h.withNetworkMode(network);
        }
        if (config.getRestartPolicy() != null && !config.getRestartPolicy().isBlank()) {
            try {
                com.github.dockerjava.api.model.RestartPolicy policy = com.github.dockerjava.api.model.RestartPolicy
                        .parse(config.getRestartPolicy());
                h.withRestartPolicy(policy);
            } catch (Exception e) {
                log.debug("Restart policy not applied: {}", e.getMessage());
            }
        }
        if (config.getLogDriver() != null && !config.getLogDriver().isBlank()) {
            Map<String, String> logOpts = new java.util.HashMap<>();
            if (config.getLogMaxSize() != null) logOpts.put("max-size", config.getLogMaxSize());
            if (config.getLogMaxFiles() != null) logOpts.put("max-file", String.valueOf(config.getLogMaxFiles()));
            try {
                h.withLogConfig(new com.github.dockerjava.api.model.LogConfig(
                        com.github.dockerjava.api.model.LogConfig.LoggingType.fromValue(config.getLogDriver()),
                        logOpts.isEmpty() ? null : logOpts
                ));
            } catch (Exception e) {
                log.debug("Skip log config: {}", e.getMessage());
            }
        }
        return h;
    }

    public java.util.Optional<String> resolveNamedVolumeForChildBinds(DockerExecutionProfileEntity profile) {
        if (profile != null && profile.getNamedVolumeForChildBinds() != null
                && !profile.getNamedVolumeForChildBinds().isBlank()) {
            return java.util.Optional.of(profile.getNamedVolumeForChildBinds().trim());
        }
        return namedVolumeForChildBinds();
    }
}
