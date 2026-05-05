package com.loadtest.execution;

import com.loadtest.execution.dto.ExecutionRequest;
import com.loadtest.execution.dto.ExecutionResponse;
import com.loadtest.execution.service.ArtifactCollectorService;
import com.loadtest.execution.service.CommandFromDbService;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectVolumeResponse;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.exception.NotModifiedException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.core.command.LogContainerResultCallback;
import com.github.dockerjava.core.command.WaitContainerResultCallback;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Objects;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.loadtest.execution.persistence.DockerExecutionProfileEntity;
import com.loadtest.execution.persistence.DockerExecutionProfileRepository;

@Slf4j
@Service
public class ContainerExecutionService {

    private final CommandFromDbService commandFromDbService;
    private final ArtifactCollectorService artifactCollector;
    private final DockerExecutionProfileRepository dockerExecutionProfileRepository;

    private final ConcurrentHashMap<String, DockerClient> dockerClients = new ConcurrentHashMap<>();

    public ContainerExecutionService(CommandFromDbService commandFromDbService,
                                     ArtifactCollectorService artifactCollector,
                                     DockerExecutionProfileRepository dockerExecutionProfileRepository) {
        this.commandFromDbService = commandFromDbService;
        this.artifactCollector = artifactCollector;
        this.dockerExecutionProfileRepository = dockerExecutionProfileRepository;
    }

    static boolean isBlankDockerHostUri(String explicitDockerHostUri) {
        return explicitDockerHostUri == null || explicitDockerHostUri.isBlank();
    }

    static boolean shouldLogWindowsDockerDesktopHint(String osName) {
        return osName != null && osName.toLowerCase().contains("win");
    }

    private static volatile String dockerBuildFailureOsNameOverrideForTests;

    static void setDockerBuildFailureOsNameOverrideForTests(String osNameOrNull) {
        dockerBuildFailureOsNameOverrideForTests = osNameOrNull;
    }

    static void clearDockerBuildFailureOsNameOverrideForTests() {
        dockerBuildFailureOsNameOverrideForTests = null;
    }

    static String dockerBuildFailureOsNameForHint() {
        String o = dockerBuildFailureOsNameOverrideForTests;
        return o != null ? o : System.getProperty("os.name");
    }

    static String stripLeadingSlashFromInspectName(String inspectName) {
        return inspectName.startsWith("/") ? inspectName.substring(1) : inspectName;
    }

    static boolean shouldParseProfileEnvironmentVariables(String environmentVariables) {
        return environmentVariables != null && !environmentVariables.isBlank();
    }

    static boolean immediateExitHasTraceback(String errorDetails) {
        return errorDetails != null && errorDetails.contains("Traceback");
    }

    static String immediateExitLogAppendix(String errorDetails) {
        return errorDetails != null ? errorDetails : "";
    }

    static boolean hasNonEmptyInspectContainerName(String name) {
        return name != null && !name.isEmpty();
    }

    static boolean shouldUseContainerIdPrefixAsDisplayName(String containerId) {
        return containerId != null && containerId.length() > 12;
    }

    static boolean shouldCollectArtifactsAfterRun(UUID taskId, String command) {
        return taskId != null && command != null && !command.isBlank();
    }

    static boolean shouldCleanupAfterRuntimeFailure(String containerId, DockerClient docker) {
        return containerId != null && docker != null;
    }

    static boolean shouldEmitContainerTracebackDetailLine(String line) {
        return !line.isBlank();
    }

    DockerClient dockerClientForUri(String explicitDockerHostUri) {
        String cacheKey = isBlankDockerHostUri(explicitDockerHostUri) ? "" : explicitDockerHostUri.trim();
        return dockerClients.computeIfAbsent(cacheKey, k -> createDockerClient(k.isEmpty() ? null : k));
    }

    protected DockerClient createDockerClient(String explicitUriOrNull) {
        return buildDockerClient(explicitUriOrNull);
    }

    static ImplicitDockerUriResolution resolveImplicitDockerUri(String osNameLower, String dockerHostFromEnv) {
        String os = osNameLower.toLowerCase();
        String dockerHost = dockerHostFromEnv == null ? "" : dockerHostFromEnv.trim();
        boolean windowsTcpDefaultApplied = false;
        if (os.contains("win") && dockerHost.isEmpty()) {
            windowsTcpDefaultApplied = true;
            dockerHost = "tcp://localhost:2375";
        }
        boolean loggedDockerHostFromEnv = dockerHostFromEnv != null && !dockerHostFromEnv.isBlank();
        URI uri;
        if (!dockerHost.isEmpty()) {
            uri = URI.create(dockerHost);
        } else {
            uri = URI.create("unix:///var/run/docker.sock");
        }
        return new ImplicitDockerUriResolution(uri, windowsTcpDefaultApplied, loggedDockerHostFromEnv);
    }

    record ImplicitDockerUriResolution(URI uri, boolean windowsTcpDefaultApplied, boolean loggedDockerHostFromEnv) {}

    static void applyImplicitDockerHostSideEffects(ImplicitDockerUriResolution implicit, String dockerHostEnvForLog) {
        if (implicit.windowsTcpDefaultApplied()) {
            System.setProperty("DOCKER_HOST", "tcp://localhost:2375");
            log.info("Windows detected: Using TCP connection to Docker (tcp://localhost:2375)");
            log.info("Make sure 'Expose daemon on tcp://localhost:2375 without TLS' is enabled in Docker Desktop");
        } else if (implicit.loggedDockerHostFromEnv()) {
            log.info("Using DOCKER_HOST from environment: {}", dockerHostEnvForLog);
        } else {
            log.info("Using default Docker connection");
        }
    }

    static URI resolveImplicitDockerUriForBuildStep() {
        String os = System.getProperty("os.name").toLowerCase();
        String dockerHostEnv = System.getenv("DOCKER_HOST");
        ImplicitDockerUriResolution implicit = resolveImplicitDockerUri(os, dockerHostEnv);
        applyImplicitDockerHostSideEffects(implicit, dockerHostEnv);
        return implicit.uri();
    }

    void pingDockerClientAfterBuild(DockerClient client, URI dockerUri) {
        try { Objects.requireNonNull(client, "client");
            client.pingCmd().exec();
            log.info("DockerClient initialized via {} (httpclient5 transport)", dockerUri);
        } catch (Exception pingException) {
            log.warn("DockerClient created but ping failed.", pingException);
        }
    }

    private DockerClient buildDockerClient(String explicitUriOrNull) {
        try {
            URI dockerUri;
            if (explicitUriOrNull != null && !explicitUriOrNull.isBlank()) {
                dockerUri = URI.create(explicitUriOrNull.trim());
                log.info("Docker client from profile URI: {}", dockerUri);
            } else {
                dockerUri = resolveImplicitDockerUriForBuildStep();
            }

            DockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                    .dockerHost(dockerUri)
                    .connectionTimeout(Duration.ofSeconds(30))
                    .responseTimeout(Duration.ofSeconds(86400))
                    .build();

            DockerClient client = DockerClientBuilder.getInstance()
                    .withDockerHttpClient(httpClient)
                    .build();

            pingDockerClientAfterBuild(client, dockerUri);
            return client;
        } catch (Exception e) {
            log.error("Failed to initialize DockerClient.", e);
            if (shouldLogWindowsDockerDesktopHint(dockerBuildFailureOsNameForHint())) {
                log.error("For Windows: enable 'Expose daemon on tcp://localhost:2375 without TLS' in Docker Desktop.");
            }
            throw new RuntimeException("Failed to initialize DockerClient. Check Docker is running and DOCKER_HOST / profile URI.", e);
        }
    }

    protected void afterContainerStartPause() throws InterruptedException {
        TimeUnit.SECONDS.sleep(2);
    }

    protected void afterImmediateExitLogDelay() throws InterruptedException {
        TimeUnit.MILLISECONDS.sleep(400);
    }

    public ExecutionResponse executeTestWithAutoCleanup(ExecutionRequest request) {
        log.info("Starting test execution with tool: {}, file: {}",
                request.getTestTool(), request.getTestFilePath());

        if (request.getCommand() == null || request.getCommand().isBlank()) {
            throw new IllegalArgumentException("Command is required (from upload request).");
        }
        if (request.getTestFilePath() == null || request.getTestFilePath().isBlank()) {
            throw new IllegalArgumentException("Test file path is required");
        }
        Path testFilePath = Paths.get(request.getTestFilePath());
        if (!Files.exists(testFilePath)) {
            throw new IllegalArgumentException("Test file not found: " + testFilePath.toAbsolutePath());
        }
        String toolName = request.getTestTool();
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("Test tool is required");
        }

        String containerId = null;
        DockerClient docker = null;
        try {
        var toolOpt = commandFromDbService.getToolByName(toolName.toUpperCase());
        if (toolOpt.isEmpty()) {
            throw new IllegalArgumentException("Unknown or disabled test tool: " + toolName + ". Add it in load_test_tools.");
        }
        var tool = toolOpt.get();

        if (request.getDockerExecutionProfileId() == null) {
            throw new IllegalArgumentException("dockerExecutionProfileId is required");
        }
        DockerExecutionProfileEntity profile = dockerExecutionProfileRepository
                .findByIdAndEnabledTrue(request.getDockerExecutionProfileId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Docker profile not found or disabled: " + request.getDockerExecutionProfileId()));
        docker = dockerClientForUri(profile.getDockerHostUri());

        var artifactPaths = commandFromDbService.resolveArtifactPaths();
        String reportsHostPath = ensureDir(artifactPaths.reportsPath());
        String metricsHostPath = ensureDir(artifactPaths.metricsPath());

        String fileName = testFilePath.getFileName().toString();
        String testFileHostPath = testFilePath.getParent().toAbsolutePath().toString();
        String containerName = buildContainerName(toolName.toLowerCase());
        String reportBaseName = containerName.replaceAll("[^a-zA-Z0-9_-]", "-");

        Map<String, String> placeholders = Map.of(
                "fileName", fileName,
                "reportBaseName", reportBaseName,
                "metricsBaseName", reportBaseName,
                "testFileHostPath", testFileHostPath,
                "reportsHostPath", reportsHostPath,
                "metricsHostPath", metricsHostPath
        );

        List<String> cmd = commandFromDbService.buildCommand(request.getCommand(), placeholders);
        if (cmd.isEmpty()) {
            throw new IllegalStateException("Command is empty after substitution. Check command in upload request.");
        }

        List<Bind> binds;
        var namedVolOpt = commandFromDbService.resolveNamedVolumeForChildBinds(profile);
        if (namedVolOpt.isPresent()) {
            try {
                InspectVolumeResponse vol = docker.inspectVolumeCmd(namedVolOpt.get()).exec();
                String mountpoint = vol.getMountpoint();
                log.info("Child tool container binds: volume '{}' → daemon mountpoint {}", namedVolOpt.get(), mountpoint);
                binds = commandFromDbService.buildBindsUsingHostPaths(placeholders, java.util.Optional.of(mountpoint));
            } catch (Exception e) {
                throw new RuntimeException(
                        "Cannot inspect Docker volume '" + namedVolOpt.get()
                                + "' for child container binds (create the volume in compose or unset LOADTEST_EXECUTION_NAMED_VOLUME_FOR_CHILD_BINDS).",
                        e);
            }
        } else {
            binds = commandFromDbService.buildBinds(placeholders);
        }
        if (binds.isEmpty()) {
            throw new IllegalStateException("No mounts: testFileHostPath, reportsHostPath or metricsHostPath are missing.");
        }

        HostConfig hostConfig = commandFromDbService.applyDockerProfile(
                HostConfig.newHostConfig().withBinds(binds), profile);

        List<String> envVars = new ArrayList<>();
        if (shouldParseProfileEnvironmentVariables(profile.getEnvironmentVariables())) {
            try {
                com.fasterxml.jackson.databind.JsonNode env = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(profile.getEnvironmentVariables());
                env.fields().forEachRemaining(e -> envVars.add(e.getKey() + "=" + e.getValue().asText()));
            } catch (Exception e) {
                log.debug("Skip env from docker profile: {}", e.getMessage());
            }
        }

        ensureImageExists(tool.getDockerImage(), docker);

        var createContainerCmd = docker.createContainerCmd(tool.getDockerImage())
                .withName(containerName)
                .withEntrypoint()
                .withCmd(cmd.toArray(new String[0]))
                .withWorkingDir("/mnt/test")
                .withHostConfig(hostConfig);
        if (!envVars.isEmpty()) {
            createContainerCmd.withEnv(envVars.toArray(new String[0]));
        }
        CreateContainerResponse container = createContainerCmd.exec();
        final String createdContainerId = container.getId();
        containerId = createdContainerId;

        log.info("Собранная команда CLI: [{}]", String.join(" ", cmd));
        docker.startContainerCmd(containerId).exec();
        log.info("Started container {} (tool={})", containerName, toolName);

        try {
            afterContainerStartPause();
            InspectContainerResponse containerInfo = docker.inspectContainerCmd(containerId).exec();
            String status = containerInfo.getState().getStatus();
            log.info("Container {} status after start: {}", containerName, status);
            if ("exited".equals(status)) {
                Integer exitCode = containerInfo.getState().getExitCode();
                log.error("Container {} exited immediately with exit code: {}", containerName, exitCode);
                afterImmediateExitLogDelay();
                String errorDetails = logContainerLogs(docker, containerId, containerName);
                if (immediateExitHasTraceback(errorDetails)) {
                    throw new RuntimeException(String.format(
                            "Container exited with code %d (см. построчный traceback в логах ERROR выше). "
                                    + "Locust: проверьте -f /mnt/test/{fileName}, --host (имя цели в сети Docker, напр. test-app-1 при поднятых test-apps), синтаксис Python.",
                            exitCode));
                }
                throw new RuntimeException(String.format(
                        "Container exited with code %d. Check test file and command. Logs: %s",
                        exitCode, immediateExitLogAppendix(errorDetails)));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cleanupContainer(docker, containerId);
            throw new RuntimeException("Test execution was interrupted", e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Failed to check container status: {}", e.getMessage());
        }

        long startTime = System.currentTimeMillis();
        log.info("Waiting for container to exit (test decides duration, e.g. Locust --run-time). Reports will be collected after exit.");
        WaitContainerResultCallback callback = new WaitContainerResultCallback();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        final DockerClient dockerForWait = docker;
        executor.submit(() -> dockerForWait.waitContainerCmd(createdContainerId).exec(callback));
        try {
            Integer exitCode = callback.awaitStatusCode();
            log.info("Container {} exited with code {}", containerId, exitCode);
        } catch (Exception e) {
            log.warn("Wait for container failed: {}", e.getMessage());
        } finally {
            executor.shutdown();
        }

        long executionTime = (System.currentTimeMillis() - startTime) / 1000;
        log.info("Test completed, execution time: {} seconds", executionTime);

        String resolvedContainerName = containerName;
        try {
            InspectContainerResponse info = docker.inspectContainerCmd(containerId).exec();
            String inspectName = info.getName();
            if (hasNonEmptyInspectContainerName(inspectName)) {
                resolvedContainerName = stripLeadingSlashFromInspectName(inspectName);
            }
            logContainerLogsForDebug(docker, containerId, resolvedContainerName);
        } catch (Exception e) {
            if (shouldUseContainerIdPrefixAsDisplayName(containerId)) {
                resolvedContainerName = containerId.substring(0, 12);
            }
            log.debug("Failed to get container name/logs: {}", e.getMessage());
        }
        String artifactBaseName = resolvedContainerName.replaceAll("[^a-zA-Z0-9_-]", "-");

        if (shouldCollectArtifactsAfterRun(request.getTaskId(), request.getCommand())) {
            try {
                Map<String, String> artifactPlaceholders = Map.of(
                        "reportBaseName", artifactBaseName,
                        "metricsBaseName", artifactBaseName,
                        "reportsHostPath", reportsHostPath,
                        "metricsHostPath", metricsHostPath
                );
                artifactCollector.collectAndSaveArtifacts(request.getTaskId(), request.getCommand(), artifactPlaceholders);
                log.info("Artifacts collected and saved for task {}", request.getTaskId());
            } catch (Exception e) {
                log.error("Failed to collect/save artifacts for task {}: {}", request.getTaskId(), e.getMessage(), e);
            }
        }

        try {
            stopContainer(docker, containerId);
            log.info("Container {} stopped", containerId);
        } catch (Exception e) {
            log.warn("Failed to stop container {}: {}", containerId, e.getMessage());
        }
        try {
            removeContainer(docker, containerId);
            log.info("Container {} removed", containerId);
        } catch (Exception e) {
            log.warn("Failed to remove container {}: {}", containerId, e.getMessage());
        }

        return ExecutionResponse.builder()
                .status("success")
                .message("Test completed successfully")
                .containerId(containerId)
                .containerName(resolvedContainerName)
                .artifactBaseName(artifactBaseName)
                .executionTime(executionTime)
                .reportsHostPath(reportsHostPath)
                .metricsHostPath(metricsHostPath)
                .build();
        } catch (RuntimeException e) {
            if (shouldCleanupAfterRuntimeFailure(containerId, docker)) {
                cleanupContainer(docker, containerId);
            }
            throw e;
        } catch (Exception e) {
            if (shouldCleanupAfterRuntimeFailure(containerId, docker)) {
                cleanupContainer(docker, containerId);
            }
            log.error("Error during test execution", e);
            throw new RuntimeException("Failed to execute test: " + e.getMessage(), e);
        }
    }

    private String ensureDir(String pathStr) { Path path = Paths.get(pathStr);
        try { if (!Files.exists(path)) {
                Files.createDirectories(path);
                log.debug("Created directory: {}", path.toAbsolutePath());
            }
            trySetWorldWritableDir(path);
        } catch (Exception e) {
            log.warn("Failed to create directory {}: {}", path, e.getMessage());
        }
        return path.toAbsolutePath().toString();
    }

    private void trySetWorldWritableDir(Path dir) {
        try {
            Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rwxrwxrwx");
            Files.setPosixFilePermissions(dir, perms);
        } catch (UnsupportedOperationException e) {
            log.debug("POSIX chmod skipped for {} (e.g. Windows host)", dir);
        } catch (Exception e) {
            log.warn("Could not set permissions on {}: {}", dir, e.getMessage());
        }
    }

    private void ensureImageExists(String imageName, DockerClient docker) {
        Objects.requireNonNull(docker, "docker");
        try {
            try {
                docker.inspectImageCmd(imageName).exec();
                log.debug("Image {} already exists locally", imageName);
                return;
            } catch (NotFoundException e) {
                log.info("Image {} not found locally. Pulling from Docker Hub...", imageName);
            }

            log.info("Pulling image {} from Docker Hub (this may take a while)...", imageName);
            docker.pullImageCmd(imageName)
                    .exec(new PullImageResultCallback())
                    .awaitCompletion();
            log.info("Image {} pulled successfully", imageName);
            
        } catch (Exception e) {
            log.error("Failed to ensure image {} exists", imageName, e);
            throw new RuntimeException("Failed to pull Docker image: " + imageName + 
                    ". Make sure Docker is running, you have internet connection, and the image name is correct.", e);
        }
    }

    private String buildContainerName(String toolPrefix) {
        return toolPrefix + "-test-" + System.currentTimeMillis();
    }

    private void stopContainer(DockerClient docker, String containerId) {
        try {
            InspectContainerResponse containerInfo = docker.inspectContainerCmd(containerId).exec();
            String status = containerInfo.getState().getStatus();

            if ("exited".equals(status) || "stopped".equals(status)) {
                log.info("Container {} is already stopped (status: {}), skipping stop command", containerId, status);
                return;
            }

            if ("running".equals(status)) {
                docker.stopContainerCmd(containerId).exec();
                log.info("Container {} stopped", containerId);
            } else {
                log.info("Container {} is in status: {}, no action needed", containerId, status);
            }
        } catch (NotModifiedException e) {
            log.debug("Container {} is already stopped (NotModifiedException)", containerId);
        } catch (NotFoundException e) {
            log.debug("Container {} not found, may have been already removed", containerId);
        } catch (Exception e) {
            log.error("Error stopping container {}", containerId, e);
            throw new RuntimeException("Failed to stop container: " + e.getMessage(), e);
        }
    }

    private void removeContainer(DockerClient docker, String containerId) {
        try {
            docker.removeContainerCmd(containerId).exec();
            log.info("Container {} removed", containerId);
        } catch (NotFoundException e) {
            log.debug("Container {} not found, may have been already removed", containerId);
        } catch (Exception e) {
            log.error("Error removing container {}", containerId, e);
            throw new RuntimeException("Failed to remove container: " + e.getMessage(), e);
        }
    }

    private void cleanupContainer(DockerClient docker, String containerId) {
        try {
            stopContainer(docker, containerId);
        } catch (Exception e) {
            log.warn("Failed to stop container {} during cleanup: {}", containerId, e.getMessage());
        }
        
        try {
            removeContainer(docker, containerId);
        } catch (Exception e) {
            log.warn("Failed to remove container {} during cleanup: {}", containerId, e.getMessage());
        }
    }

    private String logContainerLogs(DockerClient docker, String containerId, String containerName) {
        try {
            log.info("=== Container {} logs ===", containerName);
            StringBuilder logs = new StringBuilder();
            
            docker.logContainerCmd(containerId)
                    .withStdOut(true)
                    .withStdErr(true)
                    .withTailAll()
                    .exec(new LogContainerResultCallback() {
                        @Override
                        public void onNext(Frame item) {
                            logs.append(new String(item.getPayload(), StandardCharsets.UTF_8)).append("\n");
                        }
                    })
                    .awaitCompletion();
            
            String logOutput = logs.toString();
            if (!logOutput.isEmpty()) {
                if (logOutput.contains("Traceback")) {
                    log.error("Container {} logs (построчно, чтобы traceback не обрезался в консоли):", containerName);
                    for (String line : logOutput.split("\n")) {
                        if (shouldEmitContainerTracebackDetailLine(line)) {
                            log.error("[{}] {}", containerName, line);
                        }
                    }
                } else {
                    log.error("Container logs:\n{}", logOutput);
                }
            } else {
                log.warn("No logs available for container {}", containerName);
            }
            log.info("=== End of container {} logs ===", containerName);
            return logOutput;
        } catch (Exception e) {
            log.warn("Failed to retrieve logs for container {}: {}", containerName, e.getMessage());
            return null;
        }
    }

    private void logContainerLogsForDebug(DockerClient docker, String containerId, String containerName) {
        try {
            log.debug("=== Container {} execution logs (DEBUG) ===", containerName);
            StringBuilder logs = new StringBuilder();
            
            docker.logContainerCmd(containerId)
                    .withStdOut(true)
                    .withStdErr(true)
                    .withTailAll()
                    .exec(new LogContainerResultCallback() {
                        @Override
                        public void onNext(Frame item) {
                            logs.append(new String(item.getPayload(), StandardCharsets.UTF_8)).append("\n");
                        }
                    })
                    .awaitCompletion();
            
            String logOutput = logs.toString();
            if (!logOutput.isEmpty()) {
                log.debug("Container {} execution output:\n{}", containerName, logOutput);
            } else {
                log.debug("No logs available for container {}", containerName);
            }
            log.debug("=== End of container {} execution logs (DEBUG) ===", containerName);
        } catch (Exception e) {
            log.debug("Failed to retrieve logs for container {}: {}", containerName, e.getMessage());
        }
    }
    
}
