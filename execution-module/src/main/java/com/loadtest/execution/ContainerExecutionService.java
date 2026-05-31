package com.loadtest.execution;

import com.loadtest.execution.dto.ExecutionRequest;
import com.loadtest.execution.dto.ExecutionResponse;
import com.loadtest.execution.service.ArtifactCollectorService;
import com.loadtest.execution.service.CommandFromDbService;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
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
import com.loadtest.execution.util.ExecutionPlaceholderKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.loadtest.execution.persistence.DockerExecutionProfileEntity;
import com.loadtest.execution.persistence.DockerExecutionProfileRepository;
import com.loadtest.execution.persistence.LoadTestToolEntity;

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

    static void setDockerBuildFailureOsNameOverrideForTests() {
        dockerBuildFailureOsNameOverrideForTests = "Linux";
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
        } catch (RuntimeException pingException) {
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
        } catch (RuntimeException e) {
            log.error("Failed to initialize DockerClient.", e);
            if (shouldLogWindowsDockerDesktopHint(dockerBuildFailureOsNameForHint())) {
                log.error("For Windows: enable 'Expose daemon on tcp://localhost:2375 without TLS' in Docker Desktop.");
            }
            throw new ContainerExecutionException(
                    "Failed to initialize DockerClient. Check Docker is running and DOCKER_HOST / profile URI.", e);
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
                request.testTool(), request.testFilePath());
        Path testFilePath = validateExecutionRequest(request);
        ContainerRunState run = new ContainerRunState();
        try {
            PreparedExecution prepared = prepareExecution(request, testFilePath, run);
            run.containerId = createContainer(prepared);
            startCreatedContainer(prepared, run.containerId);
            checkContainerAfterStart(run, prepared);
            long executionTime = waitForContainerExit(run.docker, run.containerId);
            return finishSuccessfulExecution(request, prepared, run.containerId, executionTime);
        } catch (RuntimeException e) {
            if (shouldCleanupAfterRuntimeFailure(run.containerId, run.docker)) {
                cleanupContainer(run.docker, run.containerId);
            }
            throw e;
        }
    }

    private Path validateExecutionRequest(ExecutionRequest request) {
        if (request.command() == null || request.command().isBlank()) {
            throw new IllegalArgumentException("Command is required (from upload request).");
        }
        if (request.testFilePath() == null || request.testFilePath().isBlank()) {
            throw new IllegalArgumentException("Test file path is required");
        }
        Path testFilePath = Paths.get(request.testFilePath());
        if (!Files.exists(testFilePath)) {
            throw new IllegalArgumentException("Test file not found: " + testFilePath.toAbsolutePath());
        }
        return testFilePath;
    }

    private LoadTestToolEntity resolveTool(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("Test tool is required");
        }
        return commandFromDbService.getToolByName(toolName.toUpperCase())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown or disabled test tool: " + toolName + ". Add it in load_test_tools."));
    }

    private DockerExecutionProfileEntity resolveEnabledProfile(UUID profileId) {
        if (profileId == null) {
            throw new IllegalArgumentException("dockerExecutionProfileId is required");
        }
        return dockerExecutionProfileRepository.findByIdAndEnabledTrue(profileId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Docker profile not found or disabled: " + profileId));
    }

    private PreparedExecution prepareExecution(
            ExecutionRequest request,
            Path testFilePath,
            ContainerRunState run) {
        LoadTestToolEntity tool = resolveTool(request.testTool());
        DockerExecutionProfileEntity profile = resolveEnabledProfile(request.dockerExecutionProfileId());
        DockerClient docker = dockerClientForUri(profile.getDockerHostUri());
        run.docker = docker;

        var artifactPaths = commandFromDbService.resolveArtifactPaths();
        String reportsHostPath = ensureDir(artifactPaths.reportsPath());
        String metricsHostPath = ensureDir(artifactPaths.metricsPath());

        String fileName = testFilePath.getFileName().toString();
        String testFileHostPath = testFilePath.getParent().toAbsolutePath().toString();
        String containerName = buildContainerName(request.testTool().toLowerCase());
        String reportBaseName = containerName.replaceAll("[^a-zA-Z0-9_-]", "-");

        Map<String, String> placeholders = Map.of(
                ExecutionPlaceholderKeys.FILE_NAME, fileName,
                ExecutionPlaceholderKeys.REPORT_BASE_NAME, reportBaseName,
                ExecutionPlaceholderKeys.METRICS_BASE_NAME, reportBaseName,
                ExecutionPlaceholderKeys.TEST_FILE_HOST_PATH, testFileHostPath,
                ExecutionPlaceholderKeys.REPORTS_HOST_PATH, reportsHostPath,
                ExecutionPlaceholderKeys.METRICS_HOST_PATH, metricsHostPath
        );

        List<String> cmd = commandFromDbService.buildCommand(request.command(), placeholders);
        if (cmd.isEmpty()) {
            throw new IllegalStateException("Command is empty after substitution. Check command in upload request.");
        }

        List<Bind> binds = resolveContainerBinds(docker, profile, placeholders);
        HostConfig hostConfig = commandFromDbService.applyDockerProfile(
                HostConfig.newHostConfig().withBinds(binds), profile);

        return new PreparedExecution(
                docker,
                tool,
                profile,
                containerName,
                reportsHostPath,
                metricsHostPath,
                placeholders,
                cmd,
                hostConfig,
                parseProfileEnvironmentVariables(profile));
    }

    private List<Bind> resolveContainerBinds(
            DockerClient docker,
            DockerExecutionProfileEntity profile,
            Map<String, String> placeholders) {
        Optional<String> namedVolOpt = commandFromDbService.resolveNamedVolumeForChildBinds(profile);
        List<Bind> binds;
        if (namedVolOpt.isPresent()) {
            try {
                InspectVolumeResponse vol = docker.inspectVolumeCmd(namedVolOpt.get()).exec();
                String mountpoint = vol.getMountpoint();
                log.info("Child tool container binds: volume '{}' → daemon mountpoint {}", namedVolOpt.get(), mountpoint);
                binds = commandFromDbService.buildBindsUsingHostPaths(placeholders, Optional.of(mountpoint));
            } catch (RuntimeException e) {
                throw new ContainerExecutionException(
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
        return binds;
    }

    private static List<String> parseProfileEnvironmentVariables(DockerExecutionProfileEntity profile) {
        List<String> envVars = new ArrayList<>();
        if (!shouldParseProfileEnvironmentVariables(profile.getEnvironmentVariables())) {
            return envVars;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode env = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(profile.getEnvironmentVariables());
            env.fields().forEachRemaining(e -> envVars.add(e.getKey() + "=" + e.getValue().asText()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.debug("Skip env from docker profile: {}", e.getMessage());
        }
        return envVars;
    }

    private String createContainer(PreparedExecution prepared) {
        ensureImageExists(prepared.tool().getDockerImage(), prepared.docker());
        try (CreateContainerCmd createContainerCmd = prepared.docker().createContainerCmd(prepared.tool().getDockerImage())
                .withName(prepared.containerName())
                .withEntrypoint()
                .withCmd(prepared.cmd().toArray(new String[0]))
                .withWorkingDir("/mnt/test")
                .withHostConfig(prepared.hostConfig())) {
            if (!prepared.envVars().isEmpty()) {
                createContainerCmd.withEnv(prepared.envVars().toArray(new String[0]));
            }
            return createContainerCmd.exec().getId();
        }
    }

    private void startCreatedContainer(PreparedExecution prepared, String containerId) {
        log.info("Собранная команда CLI: [{}]", String.join(" ", prepared.cmd()));
        prepared.docker().startContainerCmd(containerId).exec();
        log.info("Started container {} (tool={})", prepared.containerName(), prepared.tool().getName());
    }

    private void checkContainerAfterStart(ContainerRunState run, PreparedExecution prepared) {
        try {
            verifyContainerDidNotExitImmediately(run.docker, run.containerId, prepared.containerName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cleanupContainer(run.docker, run.containerId);
            throw new ContainerExecutionException("Test execution was interrupted", e);
        }
    }

    private void verifyContainerDidNotExitImmediately(DockerClient docker, String containerId, String containerName)
            throws InterruptedException {
        afterContainerStartPause();
        InspectContainerResponse containerInfo = inspectContainerAfterStart(docker, containerId);
        if (containerInfo == null) {
            return;
        }
        String status = containerInfo.getState().getStatus();
        log.info("Container {} status after start: {}", containerName, status);
        if (!"exited".equals(status)) {
            return;
        }
        Integer exitCode = containerInfo.getState().getExitCode();
        log.error("Container {} exited immediately with exit code: {}", containerName, exitCode);
        afterImmediateExitLogDelay();
        String errorDetails = logContainerLogs(docker, containerId, containerName);
        throw immediateExitException(exitCode, errorDetails);
    }

    private InspectContainerResponse inspectContainerAfterStart(DockerClient docker, String containerId) {
        try {
            return docker.inspectContainerCmd(containerId).exec();
        } catch (RuntimeException e) {
            log.warn("Failed to inspect container after start (continuing): {}", e.getMessage());
            return null;
        }
    }

    private static ContainerExecutionException immediateExitException(Integer exitCode, String errorDetails) {
        if (immediateExitHasTraceback(errorDetails)) {
            return new ContainerExecutionException(String.format(
                    "Container exited with code %d (см. построчный traceback в логах ERROR выше). "
                            + "Locust: проверьте -f /mnt/test/{fileName}, --host (имя цели в сети Docker, напр. test-app-1 при поднятых test-apps), синтаксис Python.",
                    exitCode));
        }
        return new ContainerExecutionException(String.format(
                "Container exited with code %d. Check test file and command. Logs: %s",
                exitCode, immediateExitLogAppendix(errorDetails)));
    }

    private long waitForContainerExit(DockerClient docker, String containerId) {
        long startTime = System.currentTimeMillis();
        log.info("Waiting for container to exit (test decides duration, e.g. Locust --run-time). Reports will be collected after exit.");
        WaitContainerResultCallback callback = new WaitContainerResultCallback();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> docker.waitContainerCmd(containerId).exec(callback));
        try {
            Integer exitCode = callback.awaitStatusCode();
            log.info("Container {} exited with code {}", containerId, exitCode);
        } catch (RuntimeException e) {
            log.warn("Wait for container failed: {}", e.getMessage());
        } finally {
            executor.shutdown();
        }
        long executionTime = (System.currentTimeMillis() - startTime) / 1000;
        log.info("Test completed, execution time: {} seconds", executionTime);
        return executionTime;
    }

    private ExecutionResponse finishSuccessfulExecution(
            ExecutionRequest request,
            PreparedExecution prepared,
            String containerId,
            long executionTime) {
        String resolvedContainerName = resolveContainerDisplayName(prepared.docker(), containerId, prepared.containerName());
        String artifactBaseName = resolvedContainerName.replaceAll("[^a-zA-Z0-9_-]", "-");
        collectArtifactsQuietly(request, prepared.reportsHostPath(), prepared.metricsHostPath(), artifactBaseName);
        stopAndRemoveContainerQuietly(prepared.docker(), containerId);
        return new ExecutionResponse(
                "success",
                "Test completed successfully",
                containerId,
                resolvedContainerName,
                artifactBaseName,
                executionTime,
                prepared.reportsHostPath(),
                prepared.metricsHostPath());
    }

    private String resolveContainerDisplayName(DockerClient docker, String containerId, String fallbackName) {
        try {
            InspectContainerResponse info = docker.inspectContainerCmd(containerId).exec();
            String inspectName = info.getName();
            String resolved = hasNonEmptyInspectContainerName(inspectName)
                    ? stripLeadingSlashFromInspectName(inspectName)
                    : fallbackName;
            logContainerLogsForDebug(docker, containerId, resolved);
            return resolved;
        } catch (RuntimeException e) {
            if (shouldUseContainerIdPrefixAsDisplayName(containerId)) {
                return containerId.substring(0, 12);
            }
            log.debug("Failed to get container name/logs: {}", e.getMessage());
            return fallbackName;
        }
    }

    private void collectArtifactsQuietly(
            ExecutionRequest request,
            String reportsHostPath,
            String metricsHostPath,
            String artifactBaseName) {
        if (!shouldCollectArtifactsAfterRun(request.taskId(), request.command())) {
            return;
        }
        try {
            Map<String, String> artifactPlaceholders = Map.of(
                    ExecutionPlaceholderKeys.REPORT_BASE_NAME, artifactBaseName,
                    ExecutionPlaceholderKeys.METRICS_BASE_NAME, artifactBaseName,
                    ExecutionPlaceholderKeys.REPORTS_HOST_PATH, reportsHostPath,
                    ExecutionPlaceholderKeys.METRICS_HOST_PATH, metricsHostPath
            );
            artifactCollector.collectAndSaveArtifacts(request.taskId(), request.command(), artifactPlaceholders);
            log.info("Artifacts collected and saved for task {}", request.taskId());
        } catch (RuntimeException e) {
            log.error("Failed to collect/save artifacts for task {}: {}", request.taskId(), e.getMessage(), e);
        }
    }

    private void stopAndRemoveContainerQuietly(DockerClient docker, String containerId) {
        try {
            stopContainer(docker, containerId);
            log.info("Container {} stopped", containerId);
        } catch (RuntimeException e) {
            log.warn("Failed to stop container {}: {}", containerId, e.getMessage());
        }
        try {
            removeContainer(docker, containerId);
            log.info("Container {} removed", containerId);
        } catch (RuntimeException e) {
            log.warn("Failed to remove container {}: {}", containerId, e.getMessage());
        }
    }

    private record PreparedExecution(
            DockerClient docker,
            LoadTestToolEntity tool,
            DockerExecutionProfileEntity profile,
            String containerName,
            String reportsHostPath,
            String metricsHostPath,
            Map<String, String> placeholders,
            List<String> cmd,
            HostConfig hostConfig,
            List<String> envVars) {}

    private static final class ContainerRunState {
        DockerClient docker;
        String containerId;
    }

    private String ensureDir(String pathStr) { Path path = Paths.get(pathStr);
        try { if (!Files.exists(path)) {
                Files.createDirectories(path);
                log.debug("Created directory: {}", path.toAbsolutePath());
            }
            trySetWorldWritableDir(path);
        } catch (IOException e) {
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
        } catch (IOException e) {
            log.warn("Could not set permissions on {}: {}", dir, e.getMessage());
        }
    }

    private void ensureImageExists(String imageName, DockerClient docker) {
        Objects.requireNonNull(docker, "docker");
        try {
            if (!isImagePresentLocally(imageName, docker)) {
                pullImageFromRegistry(imageName, docker);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ContainerExecutionException("Interrupted while pulling Docker image: " + imageName, e);
        } catch (RuntimeException e) {
            log.error("Failed to ensure image {} exists", imageName, e);
            throw new ContainerExecutionException("Failed to pull Docker image: " + imageName
                    + ". Make sure Docker is running, you have internet connection, and the image name is correct.", e);
        }
    }

    private boolean isImagePresentLocally(String imageName, DockerClient docker) {
        try {
            docker.inspectImageCmd(imageName).exec();
            log.debug("Image {} already exists locally", imageName);
            return true;
        } catch (NotFoundException e) {
            log.info("Image {} not found locally. Pulling from Docker Hub...", imageName);
            return false;
        }
    }

    private void pullImageFromRegistry(String imageName, DockerClient docker) throws InterruptedException {
        log.info("Pulling image {} from Docker Hub (this may take a while)...", imageName);
        docker.pullImageCmd(imageName)
                .exec(new PullImageResultCallback())
                .awaitCompletion();
        log.info("Image {} pulled successfully", imageName);
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
        } catch (RuntimeException e) {
            log.error("Error stopping container {}", containerId, e);
            throw new ContainerExecutionException("Failed to stop container: " + e.getMessage(), e);
        }
    }

    private void removeContainer(DockerClient docker, String containerId) {
        try {
            docker.removeContainerCmd(containerId).exec();
            log.info("Container {} removed", containerId);
        } catch (NotFoundException e) {
            log.debug("Container {} not found, may have been already removed", containerId);
        } catch (RuntimeException e) {
            log.error("Error removing container {}", containerId, e);
            throw new ContainerExecutionException("Failed to remove container: " + e.getMessage(), e);
        }
    }

    private void cleanupContainer(DockerClient docker, String containerId) {
        try {
            stopContainer(docker, containerId);
        } catch (RuntimeException e) {
            log.warn("Failed to stop container {} during cleanup: {}", containerId, e.getMessage());
        }
        
        try {
            removeContainer(docker, containerId);
        } catch (RuntimeException e) {
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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while retrieving logs for container {}", containerName);
            return null;
        } catch (RuntimeException e) {
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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("Interrupted while retrieving logs for container {}", containerName);
        } catch (RuntimeException e) {
            log.debug("Failed to retrieve logs for container {}: {}", containerName, e.getMessage());
        }
    }

    public static class ContainerExecutionException extends RuntimeException {

        public ContainerExecutionException(String message) {
            super(message);
        }

        public ContainerExecutionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
