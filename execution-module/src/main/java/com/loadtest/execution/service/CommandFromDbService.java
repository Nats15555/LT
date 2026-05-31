package com.loadtest.execution.service;

import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.LogConfig;
import com.github.dockerjava.api.model.RestartPolicy;
import com.github.dockerjava.api.model.Volume;
import com.loadtest.execution.persistence.DockerExecutionProfileEntity;
import com.loadtest.execution.persistence.LoadTestToolEntity;
import com.loadtest.execution.persistence.LoadTestToolRepository;
import com.loadtest.execution.util.ExecutionPlaceholderKeys;
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
import java.util.HashMap;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class CommandFromDbService {

    private static final Pattern REPORT_PLACEHOLDER = Pattern.compile("\\{reportBaseName}([^\\s/\\\\]*)");
    private static final Pattern METRICS_PLACEHOLDER = Pattern.compile("\\{metricsBaseName}([^\\s/\\\\]*)");

    private final LoadTestToolRepository toolRepository;

    @Value("${file.storage.working-dir:}")
    private String workingDir;
    @Value("${file.storage.artifact-base-path:artifacts}")
    private String artifactBasePathFallback;
    @Value("${file.storage.artifact-reports-subdir:reports}")
    private String artifactReportsSubDirFallback;
    @Value("${file.storage.artifact-metrics-subdir:metrics}")
    private String artifactMetricsSubDirFallback;
    @Value("${container.default-network:}")
    private String defaultNetworkName;

    @Value("${loadtest.execution.named-volume-for-child-binds:}")
    private String namedVolumeForChildBinds;

    public record ArtifactPaths(String reportsPath, String metricsPath, String reportsSubdir, String metricsSubdir) {}

    public Optional<LoadTestToolEntity> getToolByName(String name) {
        return toolRepository.findByNameAndEnabledTrue(name);
    }

    public ArtifactPaths resolveArtifactPaths() {
        Path base = (workingDir != null && !workingDir.isBlank())
                ? Paths.get(workingDir).toAbsolutePath().normalize()
                : Paths.get(".").toAbsolutePath().normalize();
        String reportsPath = base.resolve(artifactBasePathFallback).resolve(artifactReportsSubDirFallback).toString();
        String metricsPath = base.resolve(artifactBasePathFallback).resolve(artifactMetricsSubDirFallback).toString();
        return new ArtifactPaths(reportsPath, metricsPath, artifactReportsSubDirFallback, artifactMetricsSubDirFallback);
    }

    public List<String> buildCommand(String command, Map<String, String> placeholders) {
        if (command == null || command.isBlank()) {
            return new ArrayList<>();
        }
        Map<String, String> all = new LinkedHashMap<>(placeholders != null ? placeholders : Map.of());
        String line = substitute(command, all);
        return Arrays.stream(line.split("\\s+")).filter(s -> !s.isEmpty()).toList();
    }

    private static String substitute(String s, Map<String, String> placeholders) {
        if (placeholders == null || placeholders.isEmpty()) return s;
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            s = s.replace("{" + e.getKey() + "}", e.getValue() != null ? e.getValue() : "");
        }
        return s;
    }

    public List<String> deriveArtifactFilePathsFromCommand(String commandTemplate, Map<String, String> placeholders) {
        List<String> hostPaths = new ArrayList<>();
        if (commandTemplate == null || commandTemplate.isBlank() || placeholders == null) return hostPaths;
        String reportsHostPath = placeholders.getOrDefault(ExecutionPlaceholderKeys.REPORTS_HOST_PATH, "").replace("\\", "/").replaceAll("/+$", "");
        String metricsHostPath = placeholders.getOrDefault(ExecutionPlaceholderKeys.METRICS_HOST_PATH, "").replace("\\", "/").replaceAll("/+$", "");
        String reportBase = placeholders.getOrDefault(ExecutionPlaceholderKeys.REPORT_BASE_NAME, "");
        String metricsBase = placeholders.getOrDefault(ExecutionPlaceholderKeys.METRICS_BASE_NAME, reportBase);
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

    public Optional<String> namedVolumeForChildBinds() {
        if (namedVolumeForChildBinds == null || namedVolumeForChildBinds.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(namedVolumeForChildBinds.trim());
    }

    public List<Bind> buildBinds(Map<String, String> placeholders) {
        return buildBindsUsingHostPaths(placeholders, Optional.empty());
    }

    public List<Bind> buildBindsUsingHostPaths(Map<String, String> placeholders, Optional<String> namedVolumeHostMountpoint) {
        List<Bind> binds = new ArrayList<>();
        if (placeholders == null) {
            return binds;
        }
        Function<String, String> hostPathOrSame = containerSidePath -> {
            if (containerSidePath == null || containerSidePath.isBlank()) {
                return null;
            }
            return namedVolumeHostMountpoint.map(s -> mapPathUnderWorkingDirToHostVolumeMountpoint(containerSidePath, s)).orElse(containerSidePath);
        };
        String testPath = hostPathOrSame.apply(placeholders.get(ExecutionPlaceholderKeys.TEST_FILE_HOST_PATH));
        String reportsPath = hostPathOrSame.apply(placeholders.get(ExecutionPlaceholderKeys.REPORTS_HOST_PATH));
        String metricsPath = hostPathOrSame.apply(placeholders.get(ExecutionPlaceholderKeys.METRICS_HOST_PATH));
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
        HostConfig hostConfig = base != null ? base : HostConfig.newHostConfig();
        applyMemorySettings(hostConfig, config);
        applyCpuSettings(hostConfig, config);
        applyNetworkMode(hostConfig, config);
        applyRestartPolicy(hostConfig, config);
        applyLogDriver(hostConfig, config);
        return hostConfig;
    }

    private void applyMemorySettings(HostConfig hostConfig, DockerExecutionProfileEntity config) {
        if (config.getMemoryLimitMb() != null && config.getMemoryLimitMb() > 0) {
            hostConfig.withMemory((long) config.getMemoryLimitMb() * 1024 * 1024);
        }
        if (config.getMemoryReservationMb() != null && config.getMemoryReservationMb() > 0) {
            hostConfig.withMemoryReservation((long) config.getMemoryReservationMb() * 1024 * 1024);
        }
    }

    private void applyCpuSettings(HostConfig hostConfig, DockerExecutionProfileEntity config) {
        if (config.getCpuLimit() != null && config.getCpuLimit().doubleValue() > 0) {
            try {
                hostConfig.withCpuQuota((long) (config.getCpuLimit().doubleValue() * 100_000));
                hostConfig.withCpuPeriod(100_000L);
            } catch (RuntimeException e) {
                log.debug("CPU limit not applied: {}", e.getMessage());
            }
        }
        if (config.getCpuShares() != null && config.getCpuShares() > 0) {
            hostConfig.withCpuShares(config.getCpuShares());
        }
    }

    private void applyNetworkMode(HostConfig hostConfig, DockerExecutionProfileEntity config) {
        String network = resolveNetworkMode(config);
        if (network != null) {
            hostConfig.withNetworkMode(network);
        }
    }

    private String resolveNetworkMode(DockerExecutionProfileEntity config) {
        String fromProfile = nonBlankOrNull(config.getNetworkMode());
        if (fromProfile != null) {
            return fromProfile;
        }
        return nonBlankOrNull(defaultNetworkName);
    }

    private void applyRestartPolicy(HostConfig hostConfig, DockerExecutionProfileEntity config) {
        String restartPolicy = nonBlankOrNull(config.getRestartPolicy());
        if (restartPolicy == null) {
            return;
        }
        try {
            hostConfig.withRestartPolicy(RestartPolicy.parse(restartPolicy));
        } catch (RuntimeException e) {
            log.debug("Restart policy not applied: {}", e.getMessage());
        }
    }

    private void applyLogDriver(HostConfig hostConfig, DockerExecutionProfileEntity config) {
        String logDriver = nonBlankOrNull(config.getLogDriver());
        if (logDriver == null) {
            return;
        }
        Map<String, String> logOpts = buildLogOptions(config);
        try {
            hostConfig.withLogConfig(new LogConfig(
                    LogConfig.LoggingType.fromValue(logDriver),
                    logOpts.isEmpty() ? null : logOpts
            ));
        } catch (RuntimeException e) {
            log.debug("Skip log config: {}", e.getMessage());
        }
    }

    private static Map<String, String> buildLogOptions(DockerExecutionProfileEntity config) {
        Map<String, String> logOpts = new HashMap<>();
        if (config.getLogMaxSize() != null) {
            logOpts.put("max-size", config.getLogMaxSize());
        }
        if (config.getLogMaxFiles() != null) {
            logOpts.put("max-file", String.valueOf(config.getLogMaxFiles()));
        }
        return logOpts;
    }

    private static String nonBlankOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }

    public Optional<String> resolveNamedVolumeForChildBinds(DockerExecutionProfileEntity profile) {
        if (profile != null && profile.getNamedVolumeForChildBinds() != null
                && !profile.getNamedVolumeForChildBinds().isBlank()) {
            return Optional.of(profile.getNamedVolumeForChildBinds().trim());
        }
        return namedVolumeForChildBinds();
    }
}
