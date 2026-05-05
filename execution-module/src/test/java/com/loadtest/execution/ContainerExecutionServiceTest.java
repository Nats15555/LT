package com.loadtest.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.InspectImageCmd;
import com.github.dockerjava.api.command.InspectImageResponse;
import com.github.dockerjava.api.command.InspectVolumeCmd;
import com.github.dockerjava.api.command.InspectVolumeResponse;
import com.github.dockerjava.api.command.LogContainerCmd;
import com.github.dockerjava.api.command.PingCmd;
import com.github.dockerjava.api.command.PullImageCmd;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.command.StopContainerCmd;
import com.github.dockerjava.api.command.WaitContainerCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.exception.NotModifiedException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import com.github.dockerjava.api.model.WaitResponse;
import com.github.dockerjava.core.command.LogContainerResultCallback;
import com.github.dockerjava.core.command.WaitContainerResultCallback;
import com.loadtest.execution.dto.ExecutionRequest;
import com.loadtest.execution.persistence.DockerExecutionProfileEntity;
import com.loadtest.execution.persistence.DockerExecutionProfileRepository;
import com.loadtest.execution.persistence.LoadTestToolEntity;
import com.loadtest.execution.service.ArtifactCollectorService;
import com.loadtest.execution.service.CommandFromDbService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContainerExecutionServiceTest {

    @Mock
    private CommandFromDbService commandFromDbService;
    @Mock
    private ArtifactCollectorService artifactCollectorService;
    @Mock
    private DockerExecutionProfileRepository dockerExecutionProfileRepository;

    private static final String CONTAINER_ID = "cidcidcidcidcidcidcidcid00";

    private static class TestableExecutionService extends ContainerExecutionService {
        private final DockerClient client;
        private final AtomicInteger clientCreations = new AtomicInteger();

        TestableExecutionService(
                CommandFromDbService cmd,
                ArtifactCollectorService art,
                DockerExecutionProfileRepository repo,
                DockerClient client) {
            super(cmd, art, repo);
            this.client = client;
        }

        int dockerClientCreations() {
            return clientCreations.get();
        }

        @Override
        protected DockerClient createDockerClient(String explicitUriOrNull) {
            clientCreations.incrementAndGet();
            return client;
        }

        @Override
        protected void afterContainerStartPause() {
        }

        @Override
        protected void afterImmediateExitLogDelay() {
        }
    }

    private static class DockerClientForUriFailsService extends ContainerExecutionService {
        DockerClientForUriFailsService(
                CommandFromDbService cmd,
                ArtifactCollectorService art,
                DockerExecutionProfileRepository repo) {
            super(cmd, art, repo);
        }

        @Override
        DockerClient dockerClientForUri(String explicitDockerHostUri) {
            throw new RuntimeException("docker-uri-fail");
        }

        @Override
        protected void afterContainerStartPause() {
        }

        @Override
        protected void afterImmediateExitLogDelay() {
        }
    }

    private static class NullDockerClientFromUriService extends ContainerExecutionService {
        NullDockerClientFromUriService(
                CommandFromDbService cmd,
                ArtifactCollectorService art,
                DockerExecutionProfileRepository repo) {
            super(cmd, art, repo);
        }

        @Override
        DockerClient dockerClientForUri(String explicitDockerHostUri) {
            return null;
        }

        @Override
        protected void afterContainerStartPause() {
        }

        @Override
        protected void afterImmediateExitLogDelay() {
        }
    }

    @Test
    void resolveImplicitDockerUri_windowsEmptyEnv_setsTcpDefault() {
        ContainerExecutionService.ImplicitDockerUriResolution r =
                ContainerExecutionService.resolveImplicitDockerUri("Windows 11", null);
        assertThat(r.windowsTcpDefaultApplied()).isTrue();
        assertThat(r.loggedDockerHostFromEnv()).isFalse();
        assertThat(r.uri()).isEqualTo(URI.create("tcp://localhost:2375"));
    }

    @Test
    void resolveImplicitDockerUri_windowsWithDockerHost_usesEnv() {
        ContainerExecutionService.ImplicitDockerUriResolution r =
                ContainerExecutionService.resolveImplicitDockerUri("windows", "tcp://custom:2376");
        assertThat(r.windowsTcpDefaultApplied()).isFalse();
        assertThat(r.loggedDockerHostFromEnv()).isTrue();
        assertThat(r.uri()).isEqualTo(URI.create("tcp://custom:2376"));
    }

    @Test
    void resolveImplicitDockerUri_linuxEmptyEnv_unixSocket() {
        ContainerExecutionService.ImplicitDockerUriResolution r =
                ContainerExecutionService.resolveImplicitDockerUri("Linux", "");
        assertThat(r.windowsTcpDefaultApplied()).isFalse();
        assertThat(r.loggedDockerHostFromEnv()).isFalse();
        assertThat(r.uri()).isEqualTo(URI.create("unix:///var/run/docker.sock"));
    }

    @Test
    void resolveImplicitDockerUri_linuxWithDockerHost_usesEnv() {
        ContainerExecutionService.ImplicitDockerUriResolution r =
                ContainerExecutionService.resolveImplicitDockerUri("linux", "unix:///run/docker.sock");
        assertThat(r.loggedDockerHostFromEnv()).isTrue();
        assertThat(r.uri()).isEqualTo(URI.create("unix:///run/docker.sock"));
    }

    @Test
    void resolveImplicitDockerUri_windowsWhitespaceEnv_trimsToDefaultTcp() {
        ContainerExecutionService.ImplicitDockerUriResolution r =
                ContainerExecutionService.resolveImplicitDockerUri("Windows 11", "  \t  ");
        assertThat(r.windowsTcpDefaultApplied()).isTrue();
        assertThat(r.uri()).isEqualTo(URI.create("tcp://localhost:2375"));
    }

    @Test
    void pingDockerClientAfterBuild_success_invokesPing() {
        ContainerExecutionService svc = new ContainerExecutionService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository);
        DockerClient docker = mock(DockerClient.class);
        PingCmd pingCmd = mock(PingCmd.class);
        when(docker.pingCmd()).thenReturn(pingCmd);
        svc.pingDockerClientAfterBuild(docker, URI.create("tcp://127.0.0.1:2375"));
        verify(pingCmd).exec();
    }

    @Test
    void pingDockerClientAfterBuild_pingFails_swallowsAndWarns() {
        ContainerExecutionService svc = new ContainerExecutionService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository);
        DockerClient docker = mock(DockerClient.class);
        PingCmd pingCmd = mock(PingCmd.class);
        when(docker.pingCmd()).thenReturn(pingCmd);
        doThrow(new RuntimeException("ping down")).when(pingCmd).exec();
        svc.pingDockerClientAfterBuild(docker, URI.create("tcp://127.0.0.1:2375"));
        verify(pingCmd).exec();
    }

    @Test
    void executeTest_dockerClientForUriThrows_runtimeCatchRethrows(@TempDir Path dir) throws Exception {
        Path testFile = dir.resolve("t.py");
        Files.writeString(testFile, "x");
        UUID profileId = UUID.randomUUID();
        DockerExecutionProfileEntity profile = DockerExecutionProfileEntity.builder()
                .id(profileId)
                .enabled(true)
                .build();
        LoadTestToolEntity tool = LoadTestToolEntity.builder()
                .id(UUID.randomUUID())
                .name("LOCUST")
                .dockerImage("img:latest")
                .build();
        when(commandFromDbService.getToolByName("LOCUST")).thenReturn(Optional.of(tool));
        when(dockerExecutionProfileRepository.findByIdAndEnabledTrue(profileId)).thenReturn(Optional.of(profile));
        stubCommonCommandSideEffects(dir);
        when(commandFromDbService.resolveNamedVolumeForChildBinds(profile)).thenReturn(Optional.empty());

        DockerClientForUriFailsService svc = new DockerClientForUriFailsService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository);

        assertThatThrownBy(() -> svc.executeTestWithAutoCleanup(baseRequest(testFile, profileId)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("docker-uri-fail");
    }

    @Test
    void executeTest_dockerClientForUriReturnsNull_runtimeCatchWithoutCleanup(@TempDir Path dir) throws Exception {
        Path testFile = dir.resolve("t.py");
        Files.writeString(testFile, "x");
        UUID profileId = UUID.randomUUID();
        DockerExecutionProfileEntity profile = DockerExecutionProfileEntity.builder()
                .id(profileId)
                .enabled(true)
                .build();
        LoadTestToolEntity tool = LoadTestToolEntity.builder()
                .id(UUID.randomUUID())
                .name("LOCUST")
                .dockerImage("img:latest")
                .build();
        when(commandFromDbService.getToolByName("LOCUST")).thenReturn(Optional.of(tool));
        when(dockerExecutionProfileRepository.findByIdAndEnabledTrue(profileId)).thenReturn(Optional.of(profile));
        stubCommonCommandSideEffects(dir);
        when(commandFromDbService.resolveNamedVolumeForChildBinds(profile)).thenReturn(Optional.empty());

        NullDockerClientFromUriService svc = new NullDockerClientFromUriService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository);

        assertThatThrownBy(() -> svc.executeTestWithAutoCleanup(baseRequest(testFile, profileId)))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("docker");
    }

    @Test
    void dockerClient_cachedPerServiceForBlankProfileUri(@TempDir Path dir) throws Exception {
        Path testFile = dir.resolve("t.py");
        Files.writeString(testFile, "x");
        UUID profileId = UUID.randomUUID();
        DockerExecutionProfileEntity profile = DockerExecutionProfileEntity.builder()
                .id(profileId)
                .dockerHostUri(null)
                .enabled(true)
                .build();
        LoadTestToolEntity tool = LoadTestToolEntity.builder()
                .id(UUID.randomUUID())
                .name("LOCUST")
                .dockerImage("locustio/locust:latest")
                .build();
        when(commandFromDbService.getToolByName("LOCUST")).thenReturn(Optional.of(tool));
        when(dockerExecutionProfileRepository.findByIdAndEnabledTrue(profileId)).thenReturn(Optional.of(profile));
        stubCommonCommandSideEffects(dir);

        DockerClient docker = mockHappyPathDocker();
        TestableExecutionService svc = new TestableExecutionService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository, docker);

        ExecutionRequest req = baseRequest(testFile, profileId);
        svc.executeTestWithAutoCleanup(req);
        svc.executeTestWithAutoCleanup(req);

        assertThat(svc.dockerClientCreations()).isEqualTo(1);
    }

    @Test
    void dockerClient_cachedPerServiceForWhitespaceOnlyProfileUri(@TempDir Path dir) throws Exception {
        Path testFile = dir.resolve("t.py");
        Files.writeString(testFile, "x");
        UUID profileId = UUID.randomUUID();
        DockerExecutionProfileEntity profile = DockerExecutionProfileEntity.builder()
                .id(profileId)
                .dockerHostUri("  \t  ")
                .enabled(true)
                .build();
        LoadTestToolEntity tool = LoadTestToolEntity.builder()
                .id(UUID.randomUUID())
                .name("LOCUST")
                .dockerImage("locustio/locust:latest")
                .build();
        when(commandFromDbService.getToolByName("LOCUST")).thenReturn(Optional.of(tool));
        when(dockerExecutionProfileRepository.findByIdAndEnabledTrue(profileId)).thenReturn(Optional.of(profile));
        stubCommonCommandSideEffects(dir);

        DockerClient docker = mockHappyPathDocker();
        TestableExecutionService svc = new TestableExecutionService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository, docker);

        ExecutionRequest req = baseRequest(testFile, profileId);
        svc.executeTestWithAutoCleanup(req);
        svc.executeTestWithAutoCleanup(req);

        assertThat(svc.dockerClientCreations()).isEqualTo(1);
    }

    @Test
    void executeTest_happyPath_collectsArtifacts_stopsAndRemoves(@TempDir Path dir) throws Exception {
        Path testFile = dir.resolve("t.py");
        Files.writeString(testFile, "x");
        UUID profileId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        DockerExecutionProfileEntity profile = DockerExecutionProfileEntity.builder()
                .id(profileId)
                .dockerHostUri("tcp://127.0.0.1:2375")
                .enabled(true)
                .build();
        LoadTestToolEntity tool = LoadTestToolEntity.builder()
                .id(UUID.randomUUID())
                .name("LOCUST")
                .dockerImage("locustio/locust:latest")
                .build();
        when(commandFromDbService.getToolByName("LOCUST")).thenReturn(Optional.of(tool));
        when(dockerExecutionProfileRepository.findByIdAndEnabledTrue(profileId)).thenReturn(Optional.of(profile));
        stubCommonCommandSideEffects(dir);
        when(commandFromDbService.resolveNamedVolumeForChildBinds(profile)).thenReturn(Optional.empty());

        DockerClient docker = mockHappyPathDocker();
        TestableExecutionService svc = new TestableExecutionService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository, docker);

        ExecutionRequest req = baseRequest(testFile, profileId);
        req.setTaskId(taskId);
        var response = svc.executeTestWithAutoCleanup(req);

        assertThat(response.getStatus()).isEqualTo("success");
        assertThat(response.getContainerId()).isEqualTo(CONTAINER_ID);
        verify(artifactCollectorService, times(1)).collectAndSaveArtifacts(eq(taskId), anyString(), any());
        verify(docker, times(1)).removeContainerCmd(CONTAINER_ID);
    }

    @Test
    void executeTest_happyPath_nullTaskId_skipsArtifactCollection(@TempDir Path dir) throws Exception {
        Path testFile = dir.resolve("t.py");
        Files.writeString(testFile, "x");
        UUID profileId = UUID.randomUUID();
        DockerExecutionProfileEntity profile = DockerExecutionProfileEntity.builder()
                .id(profileId)
                .dockerHostUri("tcp://127.0.0.1:2375")
                .enabled(true)
                .build();
        LoadTestToolEntity tool = LoadTestToolEntity.builder()
                .id(UUID.randomUUID())
                .name("LOCUST")
                .dockerImage("locustio/locust:latest")
                .build();
        when(commandFromDbService.getToolByName("LOCUST")).thenReturn(Optional.of(tool));
        when(dockerExecutionProfileRepository.findByIdAndEnabledTrue(profileId)).thenReturn(Optional.of(profile));
        stubCommonCommandSideEffects(dir);
        when(commandFromDbService.resolveNamedVolumeForChildBinds(profile)).thenReturn(Optional.empty());

        DockerClient docker = mockHappyPathDocker();
        TestableExecutionService svc = new TestableExecutionService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository, docker);

        ExecutionRequest req = baseRequest(testFile, profileId);
        req.setTaskId(null);
        assertThat(svc.executeTestWithAutoCleanup(req).getStatus()).isEqualTo("success");
        verify(artifactCollectorService, never()).collectAndSaveArtifacts(any(), anyString(), any());
    }

    @Test
    void executeTest_namedVolume_buildsBindsFromMountpoint(@TempDir Path dir) throws Exception {
        Path testFile = dir.resolve("t.py");
        Files.writeString(testFile, "x");
        UUID profileId = UUID.randomUUID();
        DockerExecutionProfileEntity profile = DockerExecutionProfileEntity.builder()
                .id(profileId)
                .enabled(true)
                .build();
        LoadTestToolEntity tool = LoadTestToolEntity.builder()
                .id(UUID.randomUUID())
                .name("LOCUST")
                .dockerImage("locustio/locust:latest")
                .build();
        when(commandFromDbService.getToolByName("LOCUST")).thenReturn(Optional.of(tool));
        when(dockerExecutionProfileRepository.findByIdAndEnabledTrue(profileId)).thenReturn(Optional.of(profile));
        stubCommonCommandSideEffects(dir);
        when(commandFromDbService.resolveNamedVolumeForChildBinds(profile)).thenReturn(Optional.of("named_vol"));

        InspectVolumeCmd volCmd = mock(InspectVolumeCmd.class);
        InspectVolumeResponse volResp = mock(InspectVolumeResponse.class);
        when(volResp.getMountpoint()).thenReturn("/daemon/vol/mount");
        DockerClient docker = mockHappyPathDocker();
        when(docker.inspectVolumeCmd("named_vol")).thenReturn(volCmd);
        when(volCmd.exec()).thenReturn(volResp);
        when(commandFromDbService.buildBindsUsingHostPaths(any(), eq(Optional.of("/daemon/vol/mount"))))
                .thenReturn(List.of(Bind.parse("h:/mnt/test")));

        TestableExecutionService svc = new TestableExecutionService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository, docker);
        svc.executeTestWithAutoCleanup(baseRequest(testFile, profileId));

        verify(commandFromDbService).buildBindsUsingHostPaths(any(), eq(Optional.of("/daemon/vol/mount")));
    }

    @Test
    void executeTest_volumeInspectFailure_wrapsRuntimeException(@TempDir Path dir) throws Exception {
        Path testFile = dir.resolve("t.py");
        Files.writeString(testFile, "x");
        UUID profileId = UUID.randomUUID();
        DockerExecutionProfileEntity profile = DockerExecutionProfileEntity.builder()
                .id(profileId)
                .enabled(true)
                .build();
        LoadTestToolEntity tool = LoadTestToolEntity.builder()
                .id(UUID.randomUUID())
                .name("LOCUST")
                .dockerImage("img:latest")
                .build();
        when(commandFromDbService.getToolByName("LOCUST")).thenReturn(Optional.of(tool));
        when(dockerExecutionProfileRepository.findByIdAndEnabledTrue(profileId)).thenReturn(Optional.of(profile));
        stubCommonCommandSideEffects(dir);
        when(commandFromDbService.resolveNamedVolumeForChildBinds(profile)).thenReturn(Optional.of("bad_vol"));

        DockerClient docker = mock(DockerClient.class);
        InspectVolumeCmd volCmd = mock(InspectVolumeCmd.class);
        when(docker.inspectVolumeCmd("bad_vol")).thenReturn(volCmd);
        when(volCmd.exec()).thenThrow(new NotFoundException("no volume"));

        TestableExecutionService svc = new TestableExecutionService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository, docker);

        assertThatThrownBy(() -> svc.executeTestWithAutoCleanup(baseRequest(testFile, profileId)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Cannot inspect Docker volume");
    }

    @Test
    void executeTest_emptyCmdAfterSubstitution_throws(@TempDir Path dir) throws Exception {
        Path testFile = dir.resolve("t.py");
        Files.writeString(testFile, "x");
        UUID profileId = UUID.randomUUID();
        DockerExecutionProfileEntity profile = DockerExecutionProfileEntity.builder()
                .id(profileId)
                .enabled(true)
                .build();
        LoadTestToolEntity tool = LoadTestToolEntity.builder()
                .id(UUID.randomUUID())
                .name("LOCUST")
                .dockerImage("img:latest")
                .build();
        when(commandFromDbService.getToolByName("LOCUST")).thenReturn(Optional.of(tool));
        when(dockerExecutionProfileRepository.findByIdAndEnabledTrue(profileId)).thenReturn(Optional.of(profile));
        when(commandFromDbService.resolveArtifactPaths()).thenReturn(
                new CommandFromDbService.ArtifactPaths(
                        dir.resolve("rep").toString(), dir.resolve("met").toString(), "r", "m"));
        when(commandFromDbService.buildCommand(anyString(), any())).thenReturn(List.of());
        when(commandFromDbService.resolveNamedVolumeForChildBinds(profile)).thenReturn(Optional.empty());

        TestableExecutionService svc = new TestableExecutionService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository, mock(DockerClient.class));

        assertThatThrownBy(() -> svc.executeTestWithAutoCleanup(baseRequest(testFile, profileId)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty after substitution");
    }

    @Test
    void executeTest_emptyBinds_throws(@TempDir Path dir) throws Exception {
        Path testFile = dir.resolve("t.py");
        Files.writeString(testFile, "x");
        UUID profileId = UUID.randomUUID();
        DockerExecutionProfileEntity profile = DockerExecutionProfileEntity.builder()
                .id(profileId)
                .enabled(true)
                .build();
        LoadTestToolEntity tool = LoadTestToolEntity.builder()
                .id(UUID.randomUUID())
                .name("LOCUST")
                .dockerImage("img:latest")
                .build();
        when(commandFromDbService.getToolByName("LOCUST")).thenReturn(Optional.of(tool));
        when(dockerExecutionProfileRepository.findByIdAndEnabledTrue(profileId)).thenReturn(Optional.of(profile));
        stubCommonCommandSideEffects(dir);
        when(commandFromDbService.resolveNamedVolumeForChildBinds(profile)).thenReturn(Optional.empty());
        when(commandFromDbService.buildBinds(any())).thenReturn(List.of());

        TestableExecutionService svc = new TestableExecutionService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository, mock(DockerClient.class));

        assertThatThrownBy(() -> svc.executeTestWithAutoCleanup(baseRequest(testFile, profileId)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No mounts");
    }

    @Test
    void executeTest_profileEnvJson_addsContainerEnv(@TempDir Path dir) throws Exception {
        Path testFile = dir.resolve("t.py");
        Files.writeString(testFile, "x");
        UUID profileId = UUID.randomUUID();
        DockerExecutionProfileEntity profile = DockerExecutionProfileEntity.builder()
                .id(profileId)
                .enabled(true)
                .environmentVariables(new ObjectMapper().writeValueAsString(java.util.Map.of("FOO", "bar")))
                .build();
        LoadTestToolEntity tool = LoadTestToolEntity.builder()
                .id(UUID.randomUUID())
                .name("LOCUST")
                .dockerImage("locustio/locust:latest")
                .build();
        when(commandFromDbService.getToolByName("LOCUST")).thenReturn(Optional.of(tool));
        when(dockerExecutionProfileRepository.findByIdAndEnabledTrue(profileId)).thenReturn(Optional.of(profile));
        stubCommonCommandSideEffects(dir);
        when(commandFromDbService.resolveNamedVolumeForChildBinds(profile)).thenReturn(Optional.empty());

        CreateContainerCmd createCmd = mock(CreateContainerCmd.class, RETURNS_SELF);
        CreateContainerResponse created = mock(CreateContainerResponse.class);
        when(created.getId()).thenReturn(CONTAINER_ID);
        when(createCmd.exec()).thenReturn(created);

        DockerClient docker = mockHappyPathDocker();
        when(docker.createContainerCmd(anyString())).thenReturn(createCmd);

        TestableExecutionService svc = new TestableExecutionService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository, docker);
        svc.executeTestWithAutoCleanup(baseRequest(testFile, profileId));

        verify(createCmd).withEnv(new String[]{"FOO=bar"});
    }

    @Test
    void executeTest_invalidProfileEnvJson_skipsEnv(@TempDir Path dir) throws Exception {
        Path testFile = dir.resolve("t.py");
        Files.writeString(testFile, "x");
        UUID profileId = UUID.randomUUID();
        DockerExecutionProfileEntity profile = DockerExecutionProfileEntity.builder()
                .id(profileId)
                .enabled(true)
                .environmentVariables("not-json")
                .build();
        LoadTestToolEntity tool = LoadTestToolEntity.builder()
                .id(UUID.randomUUID())
                .name("LOCUST")
                .dockerImage("locustio/locust:latest")
                .build();
        when(commandFromDbService.getToolByName("LOCUST")).thenReturn(Optional.of(tool));
        when(dockerExecutionProfileRepository.findByIdAndEnabledTrue(profileId)).thenReturn(Optional.of(profile));
        stubCommonCommandSideEffects(dir);
        when(commandFromDbService.resolveNamedVolumeForChildBinds(profile)).thenReturn(Optional.empty());

        CreateContainerCmd createCmd = mock(CreateContainerCmd.class, RETURNS_SELF);
        CreateContainerResponse created = mock(CreateContainerResponse.class);
        when(created.getId()).thenReturn(CONTAINER_ID);
        when(createCmd.exec()).thenReturn(created);

        DockerClient docker = mockHappyPathDocker();
        when(docker.createContainerCmd(anyString())).thenReturn(createCmd);

        TestableExecutionService svc = new TestableExecutionService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository, docker);
        svc.executeTestWithAutoCleanup(baseRequest(testFile, profileId));

        verify(createCmd, never()).withEnv(any(String[].class));
    }

    @Test
    void executeTest_profileEnvironmentVariablesWhitespaceOnly_skipsEnvJson(@TempDir Path dir) throws Exception {
        Path testFile = dir.resolve("t.py");
        Files.writeString(testFile, "x");
        UUID profileId = UUID.randomUUID();
        DockerExecutionProfileEntity profile = DockerExecutionProfileEntity.builder()
                .id(profileId)
                .enabled(true)
                .environmentVariables("  \t  ")
                .build();
        LoadTestToolEntity tool = LoadTestToolEntity.builder()
                .id(UUID.randomUUID())
                .name("LOCUST")
                .dockerImage("locustio/locust:latest")
                .build();
        when(commandFromDbService.getToolByName("LOCUST")).thenReturn(Optional.of(tool));
        when(dockerExecutionProfileRepository.findByIdAndEnabledTrue(profileId)).thenReturn(Optional.of(profile));
        stubCommonCommandSideEffects(dir);
        when(commandFromDbService.resolveNamedVolumeForChildBinds(profile)).thenReturn(Optional.empty());

        CreateContainerCmd createCmd = mock(CreateContainerCmd.class, RETURNS_SELF);
        CreateContainerResponse created = mock(CreateContainerResponse.class);
        when(created.getId()).thenReturn(CONTAINER_ID);
        when(createCmd.exec()).thenReturn(created);

        DockerClient docker = mockHappyPathDocker();
        when(docker.createContainerCmd(anyString())).thenReturn(createCmd);

        TestableExecutionService svc = new TestableExecutionService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository, docker);
        svc.executeTestWithAutoCleanup(baseRequest(testFile, profileId));

        verify(createCmd, never()).withEnv(any(String[].class));
    }

    @Test
    void executeTest_artifactCollectionFailure_doesNotFailResponse(@TempDir Path dir) throws Exception {
        Path testFile = dir.resolve("t.py");
        Files.writeString(testFile, "x");
        UUID profileId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        DockerExecutionProfileEntity profile = DockerExecutionProfileEntity.builder()
                .id(profileId)
                .enabled(true)
                .build();
        LoadTestToolEntity tool = LoadTestToolEntity.builder()
                .id(UUID.randomUUID())
                .name("LOCUST")
                .dockerImage("locustio/locust:latest")
                .build();
        when(commandFromDbService.getToolByName("LOCUST")).thenReturn(Optional.of(tool));
        when(dockerExecutionProfileRepository.findByIdAndEnabledTrue(profileId)).thenReturn(Optional.of(profile));
        stubCommonCommandSideEffects(dir);
        when(commandFromDbService.resolveNamedVolumeForChildBinds(profile)).thenReturn(Optional.empty());
        doThrow(new RuntimeException("artifact fail")).when(artifactCollectorService)
                .collectAndSaveArtifacts(eq(taskId), anyString(), any());

        DockerClient docker = mockHappyPathDocker();
        TestableExecutionService svc = new TestableExecutionService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository, docker);
        ExecutionRequest req = baseRequest(testFile, profileId);
        req.setTaskId(taskId);
        assertThat(svc.executeTestWithAutoCleanup(req).getStatus()).isEqualTo("success");
    }

    @Test
    void executeTest_immediateExitWithTraceback_throwsLocustHint(@TempDir Path dir) throws Exception {
        Path testFile = dir.resolve("t.py");
        Files.writeString(testFile, "x");
        UUID profileId = UUID.randomUUID();
        DockerExecutionProfileEntity profile = DockerExecutionProfileEntity.builder()
                .id(profileId)
                .enabled(true)
                .build();
        LoadTestToolEntity tool = LoadTestToolEntity.builder()
                .id(UUID.randomUUID())
                .name("LOCUST")
                .dockerImage("img:latest")
                .build();
        when(commandFromDbService.getToolByName("LOCUST")).thenReturn(Optional.of(tool));
        when(dockerExecutionProfileRepository.findByIdAndEnabledTrue(profileId)).thenReturn(Optional.of(profile));
        stubCommonCommandSideEffects(dir);
        when(commandFromDbService.resolveNamedVolumeForChildBinds(profile)).thenReturn(Optional.empty());

        DockerClient docker = mock(DockerClient.class);
        stubImagePresent(docker);
        wireCreateStart(docker);
        InspectContainerResponse exitedImmediate = exitedInspect(1);
        InspectContainerResponse exitedCleanup = exitedInspect(0);
        AtomicInteger inspectPhase = new AtomicInteger();
        InspectContainerCmd inspectCmd = mock(InspectContainerCmd.class);
        when(docker.inspectContainerCmd(anyString())).thenReturn(inspectCmd);
        when(inspectCmd.exec()).thenAnswer(inv -> {
            int n = inspectPhase.getAndIncrement();
            if (n == 0) {
                return exitedImmediate;
            }
            return exitedCleanup;
        });
        wireLogContainerForTraceback(docker);

        TestableExecutionService svc = new TestableExecutionService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository, docker);

        assertThatThrownBy(() -> svc.executeTestWithAutoCleanup(baseRequest(testFile, profileId)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("traceback");
    }

    @Test
    void executeTest_immediateExitWithoutTraceback_throwsGeneric(@TempDir Path dir) throws Exception {
        Path testFile = dir.resolve("t.py");
        Files.writeString(testFile, "x");
        UUID profileId = UUID.randomUUID();
        DockerExecutionProfileEntity profile = DockerExecutionProfileEntity.builder()
                .id(profileId)
                .enabled(true)
                .build();
        LoadTestToolEntity tool = LoadTestToolEntity.builder()
                .id(UUID.randomUUID())
                .name("LOCUST")
                .dockerImage("img:latest")
                .build();
        when(commandFromDbService.getToolByName("LOCUST")).thenReturn(Optional.of(tool));
        when(dockerExecutionProfileRepository.findByIdAndEnabledTrue(profileId)).thenReturn(Optional.of(profile));
        stubCommonCommandSideEffects(dir);
        when(commandFromDbService.resolveNamedVolumeForChildBinds(profile)).thenReturn(Optional.empty());

        DockerClient docker = mock(DockerClient.class);
        stubImagePresent(docker);
        wireCreateStart(docker);
        InspectContainerCmd inspectCmd = mock(InspectContainerCmd.class);
        when(docker.inspectContainerCmd(anyString())).thenReturn(inspectCmd);
        InspectContainerResponse exitedEarly = exitedInspect(2);
        when(inspectCmd.exec()).thenReturn(exitedEarly);
        wireLogContainerPlain(docker, "no traceback here\n");

        TestableExecutionService svc = new TestableExecutionService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository, docker);

        assertThatThrownBy(() -> svc.executeTestWithAutoCleanup(baseRequest(testFile, profileId)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("code 2");
    }

    @Test
    void executeTest_immediateExit_whenLogFetchFails_errorDetailsNull_throwsGeneric(@TempDir Path dir) throws Exception {
        Path testFile = dir.resolve("t.py");
        Files.writeString(testFile, "x");
        UUID profileId = UUID.randomUUID();
        DockerExecutionProfileEntity profile = DockerExecutionProfileEntity.builder()
                .id(profileId)
                .enabled(true)
                .build();
        LoadTestToolEntity tool = LoadTestToolEntity.builder()
                .id(UUID.randomUUID())
                .name("LOCUST")
                .dockerImage("img:latest")
                .build();
        when(commandFromDbService.getToolByName("LOCUST")).thenReturn(Optional.of(tool));
        when(dockerExecutionProfileRepository.findByIdAndEnabledTrue(profileId)).thenReturn(Optional.of(profile));
        stubCommonCommandSideEffects(dir);
        when(commandFromDbService.resolveNamedVolumeForChildBinds(profile)).thenReturn(Optional.empty());

        DockerClient docker = mock(DockerClient.class);
        stubImagePresent(docker);
        wireCreateStart(docker);
        InspectContainerCmd inspectCmd = mock(InspectContainerCmd.class);
        when(docker.inspectContainerCmd(anyString())).thenReturn(inspectCmd);
        InspectContainerResponse exitedImmediate = exitedInspect(3);
        when(inspectCmd.exec()).thenReturn(exitedImmediate);
        LogContainerCmd logCmd = mock(LogContainerCmd.class, RETURNS_SELF);
        when(docker.logContainerCmd(anyString())).thenReturn(logCmd);
        when(logCmd.exec(any(LogContainerResultCallback.class))).thenThrow(new RuntimeException("log cmd fail"));

        TestableExecutionService svc = new TestableExecutionService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository, docker);

        assertThatThrownBy(() -> svc.executeTestWithAutoCleanup(baseRequest(testFile, profileId)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("code 3")
                .hasMessageContaining("Logs: ");
    }

    @Test
    void executeTest_waitCallbackFailure_onlyWarns(@TempDir Path dir) throws Exception {
        Path testFile = dir.resolve("t.py");
        Files.writeString(testFile, "x");
        UUID profileId = UUID.randomUUID();
        DockerExecutionProfileEntity profile = DockerExecutionProfileEntity.builder()
                .id(profileId)
                .enabled(true)
                .build();
        LoadTestToolEntity tool = LoadTestToolEntity.builder()
                .id(UUID.randomUUID())
                .name("LOCUST")
                .dockerImage("img:latest")
                .build();
        when(commandFromDbService.getToolByName("LOCUST")).thenReturn(Optional.of(tool));
        when(dockerExecutionProfileRepository.findByIdAndEnabledTrue(profileId)).thenReturn(Optional.of(profile));
        stubCommonCommandSideEffects(dir);
        when(commandFromDbService.resolveNamedVolumeForChildBinds(profile)).thenReturn(Optional.empty());

        DockerClient docker = mockHappyPathDocker();
        WaitContainerCmd waitCmd = mock(WaitContainerCmd.class);
        when(docker.waitContainerCmd(CONTAINER_ID)).thenReturn(waitCmd);
        when(waitCmd.exec(any(WaitContainerResultCallback.class))).thenAnswer(inv -> {
            WaitContainerResultCallback cb = inv.getArgument(0);
            cb.onComplete();
            return null;
        });

        TestableExecutionService svc = new TestableExecutionService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository, docker);
        assertThat(svc.executeTestWithAutoCleanup(baseRequest(testFile, profileId)).getStatus()).isEqualTo("success");
    }

    @Test
    void executeTest_postStartInspectFailure_warnsAndContinues(@TempDir Path dir) throws Exception {
        Path testFile = dir.resolve("t.py");
        Files.writeString(testFile, "x");
        UUID profileId = UUID.randomUUID();
        DockerExecutionProfileEntity profile = DockerExecutionProfileEntity.builder()
                .id(profileId)
                .enabled(true)
                .build();
        LoadTestToolEntity tool = LoadTestToolEntity.builder()
                .id(UUID.randomUUID())
                .name("LOCUST")
                .dockerImage("img:latest")
                .build();
        when(commandFromDbService.getToolByName("LOCUST")).thenReturn(Optional.of(tool));
        when(dockerExecutionProfileRepository.findByIdAndEnabledTrue(profileId)).thenReturn(Optional.of(profile));
        stubCommonCommandSideEffects(dir);
        when(commandFromDbService.resolveNamedVolumeForChildBinds(profile)).thenReturn(Optional.empty());

        DockerClient docker = mockHappyPathDocker();
        InspectContainerResponse named = namedInspect("/locust-test");
        InspectContainerResponse stopExited = exitedInspect(0);
        InspectContainerCmd inspectCmd = mock(InspectContainerCmd.class);
        AtomicInteger inspectCount = new AtomicInteger();
        when(docker.inspectContainerCmd(anyString())).thenReturn(inspectCmd);
        when(inspectCmd.exec()).thenAnswer(inv -> {
            int n = inspectCount.getAndIncrement();
            if (n == 0) {
                throw new IOException("inspect transient");
            }
            if (n == 1) {
                return named;
            }
            return stopExited;
        });

        TestableExecutionService svc = new TestableExecutionService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository, docker);
        assertThat(svc.executeTestWithAutoCleanup(baseRequest(testFile, profileId)).getStatus()).isEqualTo("success");
    }

    @Test
    void executeTest_finalInspectFailure_usesShortContainerId(@TempDir Path dir) throws Exception {
        Path testFile = dir.resolve("t.py");
        Files.writeString(testFile, "x");
        UUID profileId = UUID.randomUUID();
        DockerExecutionProfileEntity profile = DockerExecutionProfileEntity.builder()
                .id(profileId)
                .enabled(true)
                .build();
        LoadTestToolEntity tool = LoadTestToolEntity.builder()
                .id(UUID.randomUUID())
                .name("LOCUST")
                .dockerImage("img:latest")
                .build();
        when(commandFromDbService.getToolByName("LOCUST")).thenReturn(Optional.of(tool));
        when(dockerExecutionProfileRepository.findByIdAndEnabledTrue(profileId)).thenReturn(Optional.of(profile));
        stubCommonCommandSideEffects(dir);
        when(commandFromDbService.resolveNamedVolumeForChildBinds(profile)).thenReturn(Optional.empty());

        DockerClient docker = mockHappyPathDocker();
        InspectContainerResponse running = runningInspect();
        InspectContainerResponse stopExited = exitedInspect(0);
        InspectContainerCmd inspectCmd = mock(InspectContainerCmd.class);
        AtomicInteger inspectCount = new AtomicInteger();
        when(docker.inspectContainerCmd(anyString())).thenReturn(inspectCmd);
        when(inspectCmd.exec()).thenAnswer(inv -> {
            int n = inspectCount.getAndIncrement();
            if (n == 0) {
                return running;
            }
            if (n == 1) {
                throw new RuntimeException("name inspect failed");
            }
            return stopExited;
        });

        TestableExecutionService svc = new TestableExecutionService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository, docker);
        var resp = svc.executeTestWithAutoCleanup(baseRequest(testFile, profileId));
        assertThat(resp.getContainerName()).isEqualTo(CONTAINER_ID.substring(0, 12));
    }

    private static final String SHORT_CONTAINER_ID = "abc123456789";

    @Test
    void executeTest_finalInspectFailure_shortContainerId_skipsSubstringPrefix(@TempDir Path dir) throws Exception {
        Path testFile = dir.resolve("t.py");
        Files.writeString(testFile, "x");
        UUID profileId = UUID.randomUUID();
        DockerExecutionProfileEntity profile = DockerExecutionProfileEntity.builder()
                .id(profileId)
                .enabled(true)
                .build();
        LoadTestToolEntity tool = LoadTestToolEntity.builder()
                .id(UUID.randomUUID())
                .name("LOCUST")
                .dockerImage("img:latest")
                .build();
        when(commandFromDbService.getToolByName("LOCUST")).thenReturn(Optional.of(tool));
        when(dockerExecutionProfileRepository.findByIdAndEnabledTrue(profileId)).thenReturn(Optional.of(profile));
        stubCommonCommandSideEffects(dir);
        when(commandFromDbService.resolveNamedVolumeForChildBinds(profile)).thenReturn(Optional.empty());

        DockerClient docker = mockHappyPathDockerWithContainerId(SHORT_CONTAINER_ID);
        InspectContainerResponse running = runningInspect();
        InspectContainerResponse stopExited = exitedInspect(0);
        InspectContainerCmd inspectCmd = mock(InspectContainerCmd.class);
        AtomicInteger inspectCount = new AtomicInteger();
        when(docker.inspectContainerCmd(anyString())).thenReturn(inspectCmd);
        when(inspectCmd.exec()).thenAnswer(inv -> {
            int n = inspectCount.getAndIncrement();
            if (n == 0) {
                return running;
            }
            if (n == 1) {
                throw new RuntimeException("name inspect failed");
            }
            return stopExited;
        });

        TestableExecutionService svc = new TestableExecutionService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository, docker);
        var resp = svc.executeTestWithAutoCleanup(baseRequest(testFile, profileId));
        assertThat(resp.getContainerName()).startsWith("locust-test-");
        assertThat(resp.getContainerName()).doesNotStartWith("abc123");
    }

    @Test
    void executeTest_finalInspect_nameEmptyString_keepsBuiltContainerName(@TempDir Path dir) throws Exception {
        Path testFile = dir.resolve("t.py");
        Files.writeString(testFile, "x");
        UUID profileId = UUID.randomUUID();
        DockerExecutionProfileEntity profile = DockerExecutionProfileEntity.builder()
                .id(profileId)
                .enabled(true)
                .build();
        LoadTestToolEntity tool = LoadTestToolEntity.builder()
                .id(UUID.randomUUID())
                .name("LOCUST")
                .dockerImage("img:latest")
                .build();
        when(commandFromDbService.getToolByName("LOCUST")).thenReturn(Optional.of(tool));
        when(dockerExecutionProfileRepository.findByIdAndEnabledTrue(profileId)).thenReturn(Optional.of(profile));
        stubCommonCommandSideEffects(dir);
        when(commandFromDbService.resolveNamedVolumeForChildBinds(profile)).thenReturn(Optional.empty());

        DockerClient docker = mockHappyPathDocker();
        InspectContainerResponse postStartRunning = runningInspect();
        InspectContainerResponse namedEmpty = namedInspect("");
        AtomicInteger inspectPhase = new AtomicInteger();
        InspectContainerCmd inspectCmd = mock(InspectContainerCmd.class);
        when(docker.inspectContainerCmd(anyString())).thenReturn(inspectCmd);
        when(inspectCmd.exec()).thenAnswer(inv -> {
            int n = inspectPhase.getAndIncrement();
            int cycle = n % 3;
            if (cycle == 0) {
                return postStartRunning;
            }
            if (cycle == 1) {
                return namedEmpty;
            }
            return postStartRunning;
        });
        WaitContainerCmd waitCmd = mock(WaitContainerCmd.class);
        when(docker.waitContainerCmd(CONTAINER_ID)).thenReturn(waitCmd);
        WaitResponse waitResponse = mock(WaitResponse.class);
        lenient().when(waitResponse.getStatusCode()).thenReturn(0);
        when(waitCmd.exec(any(WaitContainerResultCallback.class))).thenAnswer(inv -> {
            WaitContainerResultCallback cb = inv.getArgument(0);
            cb.onNext(waitResponse);
            cb.onComplete();
            return null;
        });
        wireLogContainerEmpty(docker);
        StopContainerCmd stopCmd = mock(StopContainerCmd.class);
        RemoveContainerCmd removeCmd = mock(RemoveContainerCmd.class);
        when(docker.stopContainerCmd(anyString())).thenReturn(stopCmd);
        when(docker.removeContainerCmd(anyString())).thenReturn(removeCmd);

        TestableExecutionService svc = new TestableExecutionService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository, docker);
        var resp = svc.executeTestWithAutoCleanup(baseRequest(testFile, profileId));
        assertThat(resp.getContainerName()).startsWith("locust-test-");
    }

    @Test
    void executeTest_finalInspect_nameNull_keepsBuiltContainerName(@TempDir Path dir) throws Exception {
        Path testFile = dir.resolve("t.py");
        Files.writeString(testFile, "x");
        UUID profileId = UUID.randomUUID();
        DockerExecutionProfileEntity profile = DockerExecutionProfileEntity.builder()
                .id(profileId)
                .enabled(true)
                .build();
        LoadTestToolEntity tool = LoadTestToolEntity.builder()
                .id(UUID.randomUUID())
                .name("LOCUST")
                .dockerImage("img:latest")
                .build();
        when(commandFromDbService.getToolByName("LOCUST")).thenReturn(Optional.of(tool));
        when(dockerExecutionProfileRepository.findByIdAndEnabledTrue(profileId)).thenReturn(Optional.of(profile));
        stubCommonCommandSideEffects(dir);
        when(commandFromDbService.resolveNamedVolumeForChildBinds(profile)).thenReturn(Optional.empty());

        DockerClient docker = mockHappyPathDocker();
        InspectContainerResponse postStartRunning = runningInspect();
        InspectContainerResponse namedNull = mock(InspectContainerResponse.class);
        when(namedNull.getName()).thenReturn(null);
        InspectContainerResponse.ContainerState st = mock(InspectContainerResponse.ContainerState.class);
        when(st.getStatus()).thenReturn("running");
        when(namedNull.getState()).thenReturn(st);
        AtomicInteger inspectPhase = new AtomicInteger();
        InspectContainerCmd inspectCmd = mock(InspectContainerCmd.class);
        when(docker.inspectContainerCmd(anyString())).thenReturn(inspectCmd);
        when(inspectCmd.exec()).thenAnswer(inv -> {
            int n = inspectPhase.getAndIncrement();
            int cycle = n % 3;
            if (cycle == 0) {
                return postStartRunning;
            }
            if (cycle == 1) {
                return namedNull;
            }
            return postStartRunning;
        });
        WaitContainerCmd waitCmd = mock(WaitContainerCmd.class);
        when(docker.waitContainerCmd(CONTAINER_ID)).thenReturn(waitCmd);
        WaitResponse waitResponse = mock(WaitResponse.class);
        lenient().when(waitResponse.getStatusCode()).thenReturn(0);
        when(waitCmd.exec(any(WaitContainerResultCallback.class))).thenAnswer(inv -> {
            WaitContainerResultCallback cb = inv.getArgument(0);
            cb.onNext(waitResponse);
            cb.onComplete();
            return null;
        });
        wireLogContainerEmpty(docker);
        StopContainerCmd stopCmd = mock(StopContainerCmd.class);
        RemoveContainerCmd removeCmd = mock(RemoveContainerCmd.class);
        when(docker.stopContainerCmd(anyString())).thenReturn(stopCmd);
        when(docker.removeContainerCmd(anyString())).thenReturn(removeCmd);

        TestableExecutionService svc = new TestableExecutionService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository, docker);
        var resp = svc.executeTestWithAutoCleanup(baseRequest(testFile, profileId));
        assertThat(resp.getContainerName()).startsWith("locust-test-");
    }

    @Test
    void executeTest_startThrowsException_cleanupInvoked(@TempDir Path dir) throws Exception {
        Path testFile = dir.resolve("t.py");
        Files.writeString(testFile, "x");
        UUID profileId = UUID.randomUUID();
        DockerExecutionProfileEntity profile = DockerExecutionProfileEntity.builder()
                .id(profileId)
                .enabled(true)
                .build();
        LoadTestToolEntity tool = LoadTestToolEntity.builder()
                .id(UUID.randomUUID())
                .name("LOCUST")
                .dockerImage("img:latest")
                .build();
        when(commandFromDbService.getToolByName("LOCUST")).thenReturn(Optional.of(tool));
        when(dockerExecutionProfileRepository.findByIdAndEnabledTrue(profileId)).thenReturn(Optional.of(profile));
        stubCommonCommandSideEffects(dir);
        when(commandFromDbService.resolveNamedVolumeForChildBinds(profile)).thenReturn(Optional.empty());

        DockerClient docker = mock(DockerClient.class);
        stubImagePresent(docker);
        CreateContainerCmd createCmd = mock(CreateContainerCmd.class, RETURNS_SELF);
        CreateContainerResponse created = mock(CreateContainerResponse.class);
        when(created.getId()).thenReturn(CONTAINER_ID);
        when(createCmd.exec()).thenReturn(created);
        when(docker.createContainerCmd(anyString())).thenReturn(createCmd);
        StartContainerCmd startCmd = mock(StartContainerCmd.class);
        when(docker.startContainerCmd(CONTAINER_ID)).thenReturn(startCmd);
        when(startCmd.exec()).thenThrow(new RuntimeException("start failed"));

        StopContainerCmd stopCmd = mock(StopContainerCmd.class);
        RemoveContainerCmd removeCmd = mock(RemoveContainerCmd.class);
        when(docker.stopContainerCmd(anyString())).thenReturn(stopCmd);
        when(docker.removeContainerCmd(anyString())).thenReturn(removeCmd);
        InspectContainerCmd inspectCmd = mock(InspectContainerCmd.class);
        when(docker.inspectContainerCmd(anyString())).thenReturn(inspectCmd);
        InspectContainerResponse cleanupInspect = exitedInspect(0);
        when(inspectCmd.exec()).thenReturn(cleanupInspect);

        TestableExecutionService svc = new TestableExecutionService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository, docker);

        assertThatThrownBy(() -> svc.executeTestWithAutoCleanup(baseRequest(testFile, profileId)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("start failed");

        verify(removeCmd, times(1)).exec();
    }

    @Test
    void executeTest_removeContainerFailure_isSwallowed(@TempDir Path dir) throws Exception {
        Path testFile = dir.resolve("t.py");
        Files.writeString(testFile, "x");
        UUID profileId = UUID.randomUUID();
        DockerExecutionProfileEntity profile = DockerExecutionProfileEntity.builder()
                .id(profileId)
                .enabled(true)
                .build();
        LoadTestToolEntity tool = LoadTestToolEntity.builder()
                .id(UUID.randomUUID())
                .name("LOCUST")
                .dockerImage("img:latest")
                .build();
        when(commandFromDbService.getToolByName("LOCUST")).thenReturn(Optional.of(tool));
        when(dockerExecutionProfileRepository.findByIdAndEnabledTrue(profileId)).thenReturn(Optional.of(profile));
        stubCommonCommandSideEffects(dir);
        when(commandFromDbService.resolveNamedVolumeForChildBinds(profile)).thenReturn(Optional.empty());

        DockerClient docker = mockHappyPathDocker();
        RemoveContainerCmd removeCmd = mock(RemoveContainerCmd.class);
        when(docker.removeContainerCmd(CONTAINER_ID)).thenReturn(removeCmd);
        when(removeCmd.exec()).thenThrow(new RuntimeException("remove boom"));

        TestableExecutionService svc = new TestableExecutionService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository, docker);
        assertThat(svc.executeTestWithAutoCleanup(baseRequest(testFile, profileId)).getStatus()).isEqualTo("success");
    }

    @Test
    void executeTest_stopInspectFailureDuringCleanup_stillRemoves(@TempDir Path dir) throws Exception {
        Path testFile = dir.resolve("t.py");
        Files.writeString(testFile, "x");
        UUID profileId = UUID.randomUUID();
        DockerExecutionProfileEntity profile = DockerExecutionProfileEntity.builder()
                .id(profileId)
                .enabled(true)
                .build();
        LoadTestToolEntity tool = LoadTestToolEntity.builder()
                .id(UUID.randomUUID())
                .name("LOCUST")
                .dockerImage("img:latest")
                .build();
        when(commandFromDbService.getToolByName("LOCUST")).thenReturn(Optional.of(tool));
        when(dockerExecutionProfileRepository.findByIdAndEnabledTrue(profileId)).thenReturn(Optional.of(profile));
        stubCommonCommandSideEffects(dir);
        when(commandFromDbService.resolveNamedVolumeForChildBinds(profile)).thenReturn(Optional.empty());

        DockerClient docker = mockHappyPathDocker();
        StopContainerCmd stopCmd = mock(StopContainerCmd.class);
        when(docker.stopContainerCmd(CONTAINER_ID)).thenReturn(stopCmd);
        when(stopCmd.exec()).thenThrow(new RuntimeException("stop boom"));

        TestableExecutionService svc = new TestableExecutionService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository, docker);
        assertThat(svc.executeTestWithAutoCleanup(baseRequest(testFile, profileId)).getStatus()).isEqualTo("success");
        verify(docker, times(1)).removeContainerCmd(CONTAINER_ID);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void afterContainerStartPause_defaultImplementation_sleepsTwoSeconds() throws Exception {
        ContainerExecutionService raw =
                new ContainerExecutionService(commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository);
        Method m = ContainerExecutionService.class.getDeclaredMethod("afterContainerStartPause");
        m.setAccessible(true);
        long t0 = System.currentTimeMillis();
        m.invoke(raw);
        assertThat(System.currentTimeMillis() - t0).isGreaterThanOrEqualTo(1900L);
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void afterImmediateExitLogDelay_defaultImplementation_sleeps() throws Exception {
        ContainerExecutionService raw =
                new ContainerExecutionService(commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository);
        Method m = ContainerExecutionService.class.getDeclaredMethod("afterImmediateExitLogDelay");
        m.setAccessible(true);
        long t0 = System.currentTimeMillis();
        m.invoke(raw);
        assertThat(System.currentTimeMillis() - t0).isGreaterThanOrEqualTo(350L);
    }

    private static final class InterruptAfterStartPauseService extends ContainerExecutionService {
        private final DockerClient dockerClient;

        InterruptAfterStartPauseService(
                CommandFromDbService cmd,
                ArtifactCollectorService art,
                DockerExecutionProfileRepository repo,
                DockerClient dockerClient) {
            super(cmd, art, repo);
            this.dockerClient = dockerClient;
        }

        @Override
        protected DockerClient createDockerClient(String explicitUriOrNull) {
            return dockerClient;
        }

        @Override
        protected void afterContainerStartPause() throws InterruptedException {
            throw new InterruptedException("pause-int");
        }

        @Override
        protected void afterImmediateExitLogDelay() {
        }
    }

    @Test
    void executeTest_interruptedAfterStart_triggersCleanupAndRuntime(@TempDir Path dir) throws Exception {
        Path testFile = dir.resolve("t.py");
        Files.writeString(testFile, "x");
        UUID profileId = UUID.randomUUID();
        DockerExecutionProfileEntity profile = DockerExecutionProfileEntity.builder()
                .id(profileId)
                .enabled(true)
                .dockerHostUri("tcp://127.0.0.1:2375")
                .build();
        LoadTestToolEntity tool = LoadTestToolEntity.builder()
                .id(UUID.randomUUID())
                .name("LOCUST")
                .dockerImage("img:latest")
                .build();
        when(commandFromDbService.getToolByName("LOCUST")).thenReturn(Optional.of(tool));
        when(dockerExecutionProfileRepository.findByIdAndEnabledTrue(profileId)).thenReturn(Optional.of(profile));
        stubCommonCommandSideEffects(dir);
        when(commandFromDbService.resolveNamedVolumeForChildBinds(profile)).thenReturn(Optional.empty());

        DockerClient docker = mock(DockerClient.class);
        stubImagePresent(docker);
        wireCreateStart(docker);
        InspectContainerCmd inspectCleanup = mock(InspectContainerCmd.class);
        when(docker.inspectContainerCmd(anyString())).thenReturn(inspectCleanup);
        InspectContainerResponse runningCleanup = runningInspect();
        when(inspectCleanup.exec()).thenReturn(runningCleanup);
        StopContainerCmd stopCmd = mock(StopContainerCmd.class);
        RemoveContainerCmd removeCmd = mock(RemoveContainerCmd.class);
        when(docker.stopContainerCmd(anyString())).thenReturn(stopCmd);
        when(docker.removeContainerCmd(anyString())).thenReturn(removeCmd);

        InterruptAfterStartPauseService svc = new InterruptAfterStartPauseService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository, docker);

        assertThatThrownBy(() -> svc.executeTestWithAutoCleanup(baseRequest(testFile, profileId)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("interrupted");
        verify(stopCmd, times(2)).exec();
        verify(removeCmd, times(2)).exec();
    }

    @Test
    void executeTest_createContainerExecThrowsRuntime_wrapsFailedToExecute(@TempDir Path dir) throws Exception {
        Path testFile = dir.resolve("t.py");
        Files.writeString(testFile, "x");
        UUID profileId = UUID.randomUUID();
        DockerExecutionProfileEntity profile = DockerExecutionProfileEntity.builder()
                .id(profileId)
                .enabled(true)
                .build();
        LoadTestToolEntity tool = LoadTestToolEntity.builder()
                .id(UUID.randomUUID())
                .name("LOCUST")
                .dockerImage("img:latest")
                .build();
        when(commandFromDbService.getToolByName("LOCUST")).thenReturn(Optional.of(tool));
        when(dockerExecutionProfileRepository.findByIdAndEnabledTrue(profileId)).thenReturn(Optional.of(profile));
        stubCommonCommandSideEffects(dir);
        when(commandFromDbService.resolveNamedVolumeForChildBinds(profile)).thenReturn(Optional.empty());

        DockerClient docker = mock(DockerClient.class);
        stubImagePresent(docker);
        CreateContainerCmd createCmd = mock(CreateContainerCmd.class, RETURNS_SELF);
        when(docker.createContainerCmd(anyString())).thenReturn(createCmd);
        when(createCmd.exec()).thenThrow(new RuntimeException("cce"));

        TestableExecutionService svc = new TestableExecutionService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository, docker);

        assertThatThrownBy(() -> svc.executeTestWithAutoCleanup(baseRequest(testFile, profileId)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("cce");
    }

    @Test
    void executeTest_createContainerExecThrowsIOException_outerCatchNoCleanup(@TempDir Path dir) throws Exception {
        Path testFile = dir.resolve("t.py");
        Files.writeString(testFile, "x");
        UUID profileId = UUID.randomUUID();
        DockerExecutionProfileEntity profile = DockerExecutionProfileEntity.builder()
                .id(profileId)
                .enabled(true)
                .build();
        LoadTestToolEntity tool = LoadTestToolEntity.builder()
                .id(UUID.randomUUID())
                .name("LOCUST")
                .dockerImage("img:latest")
                .build();
        when(commandFromDbService.getToolByName("LOCUST")).thenReturn(Optional.of(tool));
        when(dockerExecutionProfileRepository.findByIdAndEnabledTrue(profileId)).thenReturn(Optional.of(profile));
        stubCommonCommandSideEffects(dir);
        when(commandFromDbService.resolveNamedVolumeForChildBinds(profile)).thenReturn(Optional.empty());

        DockerClient docker = mock(DockerClient.class);
        stubImagePresent(docker);
        CreateContainerCmd createCmd = mock(CreateContainerCmd.class, RETURNS_SELF);
        when(docker.createContainerCmd(anyString())).thenReturn(createCmd);
        when(createCmd.exec()).thenAnswer(inv -> {
            throw new IOException("create io");
        });

        TestableExecutionService svc = new TestableExecutionService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository, docker);

        assertThatThrownBy(() -> svc.executeTestWithAutoCleanup(baseRequest(testFile, profileId)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to execute test")
                .hasMessageContaining("create io");
        verify(docker, never()).startContainerCmd(anyString());
        verify(docker, never()).stopContainerCmd(anyString());
        verify(docker, never()).removeContainerCmd(anyString());
    }

    @Test
    void executeTest_startThrowsIOException_outerCatchCleanup(@TempDir Path dir) throws Exception {
        Path testFile = dir.resolve("t.py");
        Files.writeString(testFile, "x");
        UUID profileId = UUID.randomUUID();
        DockerExecutionProfileEntity profile = DockerExecutionProfileEntity.builder()
                .id(profileId)
                .enabled(true)
                .build();
        LoadTestToolEntity tool = LoadTestToolEntity.builder()
                .id(UUID.randomUUID())
                .name("LOCUST")
                .dockerImage("img:latest")
                .build();
        when(commandFromDbService.getToolByName("LOCUST")).thenReturn(Optional.of(tool));
        when(dockerExecutionProfileRepository.findByIdAndEnabledTrue(profileId)).thenReturn(Optional.of(profile));
        stubCommonCommandSideEffects(dir);
        when(commandFromDbService.resolveNamedVolumeForChildBinds(profile)).thenReturn(Optional.empty());

        DockerClient docker = mockHappyPathDocker();
        StartContainerCmd startCmd = mock(StartContainerCmd.class);
        when(docker.startContainerCmd(CONTAINER_ID)).thenReturn(startCmd);
        when(startCmd.exec()).thenAnswer(inv -> {
            throw new IOException("start io");
        });

        StopContainerCmd stopCmd = mock(StopContainerCmd.class);
        RemoveContainerCmd removeCmd = mock(RemoveContainerCmd.class);
        when(docker.stopContainerCmd(anyString())).thenReturn(stopCmd);
        when(docker.removeContainerCmd(anyString())).thenReturn(removeCmd);
        InspectContainerResponse runningForCleanup = runningInspect();
        InspectContainerCmd inspectCleanup = mock(InspectContainerCmd.class);
        when(docker.inspectContainerCmd(anyString())).thenReturn(inspectCleanup);
        when(inspectCleanup.exec()).thenReturn(runningForCleanup);

        TestableExecutionService svc = new TestableExecutionService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository, docker);

        assertThatThrownBy(() -> svc.executeTestWithAutoCleanup(baseRequest(testFile, profileId)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to execute test")
                .hasMessageContaining("start io");
        verify(stopCmd, atLeastOnce()).exec();
        verify(removeCmd, atLeastOnce()).exec();
    }

    @Test
    void executeTest_stopContainerFailureAtEnd_isSwallowed(@TempDir Path dir) throws Exception {
        Path testFile = dir.resolve("t.py");
        Files.writeString(testFile, "x");
        UUID profileId = UUID.randomUUID();
        DockerExecutionProfileEntity profile = DockerExecutionProfileEntity.builder()
                .id(profileId)
                .enabled(true)
                .build();
        LoadTestToolEntity tool = LoadTestToolEntity.builder()
                .id(UUID.randomUUID())
                .name("LOCUST")
                .dockerImage("img:latest")
                .build();
        when(commandFromDbService.getToolByName("LOCUST")).thenReturn(Optional.of(tool));
        when(dockerExecutionProfileRepository.findByIdAndEnabledTrue(profileId)).thenReturn(Optional.of(profile));
        stubCommonCommandSideEffects(dir);
        when(commandFromDbService.resolveNamedVolumeForChildBinds(profile)).thenReturn(Optional.empty());

        DockerClient docker = mockHappyPathDocker();
        StopContainerCmd stopCmd = mock(StopContainerCmd.class);
        when(docker.stopContainerCmd(CONTAINER_ID)).thenReturn(stopCmd);
        when(stopCmd.exec()).thenThrow(new RuntimeException("stop end boom"));

        TestableExecutionService svc = new TestableExecutionService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository, docker);
        assertThat(svc.executeTestWithAutoCleanup(baseRequest(testFile, profileId)).getStatus()).isEqualTo("success");
    }

    @Test
    void executeTest_imageAlreadyPresent_skipsPull(@TempDir Path dir) throws Exception {
        Path testFile = dir.resolve("t.py");
        Files.writeString(testFile, "x");
        UUID profileId = UUID.randomUUID();
        DockerExecutionProfileEntity profile = DockerExecutionProfileEntity.builder()
                .id(profileId)
                .enabled(true)
                .build();
        LoadTestToolEntity tool = LoadTestToolEntity.builder()
                .id(UUID.randomUUID())
                .name("LOCUST")
                .dockerImage("locustio/locust:latest")
                .build();
        when(commandFromDbService.getToolByName("LOCUST")).thenReturn(Optional.of(tool));
        when(dockerExecutionProfileRepository.findByIdAndEnabledTrue(profileId)).thenReturn(Optional.of(profile));
        stubCommonCommandSideEffects(dir);
        when(commandFromDbService.resolveNamedVolumeForChildBinds(profile)).thenReturn(Optional.empty());

        DockerClient docker = mock(DockerClient.class);
        stubImagePresent(docker);
        wireCreateStart(docker);
        InspectContainerResponse running = runningInspect();
        InspectContainerResponse named = namedInspect("/locust-test-xyz");
        InspectContainerResponse stopExited = exitedInspect(0);
        AtomicInteger inspectPhase = new AtomicInteger();
        InspectContainerCmd inspectCmd = mock(InspectContainerCmd.class);
        when(docker.inspectContainerCmd(anyString())).thenReturn(inspectCmd);
        when(inspectCmd.exec()).thenAnswer(inv -> {
            int n = inspectPhase.getAndIncrement();
            int c = n % 3;
            if (c == 0) {
                return running;
            }
            if (c == 1) {
                return named;
            }
            return stopExited;
        });
        WaitContainerCmd waitCmd = mock(WaitContainerCmd.class);
        when(docker.waitContainerCmd(CONTAINER_ID)).thenReturn(waitCmd);
        WaitResponse waitResponse = mock(WaitResponse.class);
        lenient().when(waitResponse.getStatusCode()).thenReturn(0);
        when(waitCmd.exec(any(WaitContainerResultCallback.class))).thenAnswer(inv -> {
            WaitContainerResultCallback cb = inv.getArgument(0);
            cb.onNext(waitResponse);
            cb.onComplete();
            return null;
        });
        wireLogContainerEmpty(docker);
        StopContainerCmd stopCmd = mock(StopContainerCmd.class);
        RemoveContainerCmd removeCmd = mock(RemoveContainerCmd.class);
        when(docker.stopContainerCmd(anyString())).thenReturn(stopCmd);
        when(docker.removeContainerCmd(anyString())).thenReturn(removeCmd);

        TestableExecutionService svc = new TestableExecutionService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository, docker);
        svc.executeTestWithAutoCleanup(baseRequest(testFile, profileId));
        verify(docker, never()).pullImageCmd(anyString());
    }

    @Test
    void ensureDir_createDirectoriesFailure_warnsAndReturnsPath(@TempDir Path dir) throws Exception {
        Method m = ContainerExecutionService.class.getDeclaredMethod("ensureDir", String.class);
        m.setAccessible(true);
        TestableExecutionService svc = new TestableExecutionService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository, mock(DockerClient.class));
        Path target = dir.resolve("nested-x");
        try (MockedStatic<Files> files = mockStatic(Files.class, Mockito.withSettings().defaultAnswer(Mockito.CALLS_REAL_METHODS))) {
            files.when(() -> Files.exists(any(Path.class))).thenReturn(false);
            files.when(() -> Files.createDirectories(any(Path.class), any())).thenThrow(new IOException("mkdir fail"));
            String out = (String) m.invoke(svc, target.toString());
            assertThat(out).contains(target.getFileName().toString());
        }
    }

    @Test
    void ensureDir_whenPathMissing_createsDirectory(@TempDir Path dir) throws Exception {
        Method m = ContainerExecutionService.class.getDeclaredMethod("ensureDir", String.class);
        m.setAccessible(true);
        ContainerExecutionService svc = new ContainerExecutionService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository);
        Path missing = dir.resolve("nested-ensure").resolve("leaf");
        assertThat(Files.exists(missing)).isFalse();
        String out = (String) m.invoke(svc, missing.toString());
        assertThat(Files.isDirectory(missing)).isTrue();
        assertThat(Paths.get(out).toAbsolutePath().normalize()).isEqualTo(missing.toAbsolutePath().normalize());
    }

    @Test
    void ensureDir_whenPathAlreadyExists_skipsCreateDirectories(@TempDir Path dir) throws Exception {
        Method m = ContainerExecutionService.class.getDeclaredMethod("ensureDir", String.class);
        m.setAccessible(true);
        ContainerExecutionService svc = new ContainerExecutionService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository);
        assertThat(Files.isDirectory(dir)).isTrue();
        String out = (String) m.invoke(svc, dir.toString());
        assertThat(Paths.get(out).toAbsolutePath().normalize()).isEqualTo(dir.toAbsolutePath().normalize());
    }

    @Test
    void ensureDir_filesExistsThrows_warnsAndReturnsAbsolutePath(@TempDir Path dir) throws Exception {
        Method m = ContainerExecutionService.class.getDeclaredMethod("ensureDir", String.class);
        m.setAccessible(true);
        TestableExecutionService svc = new TestableExecutionService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository, mock(DockerClient.class));
        Path target = dir.resolve("stat-fail");
        try (MockedStatic<Files> files = mockStatic(Files.class, Mockito.withSettings().defaultAnswer(Mockito.CALLS_REAL_METHODS))) {
            files.when(() -> Files.exists(any(Path.class))).thenAnswer(inv -> {
                throw new IOException("exists boom");
            });
            String out = (String) m.invoke(svc, target.toString());
            assertThat(Paths.get(out).toAbsolutePath().normalize()).isEqualTo(target.toAbsolutePath().normalize());
        }
    }

    @Test
    void trySetWorldWritableDir_posixUnsupported_logsDebug(@TempDir Path dir) throws Exception {
        Method m = ContainerExecutionService.class.getDeclaredMethod("trySetWorldWritableDir", Path.class);
        m.setAccessible(true);
        TestableExecutionService svc = new TestableExecutionService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository, mock(DockerClient.class));
        try (MockedStatic<Files> files = mockStatic(Files.class, Mockito.withSettings().defaultAnswer(Mockito.CALLS_REAL_METHODS))) {
            files.when(() -> Files.setPosixFilePermissions(any(Path.class), any()))
                    .thenThrow(new UnsupportedOperationException("no posix"));
            m.invoke(svc, dir);
        }
    }

    @Test
    void trySetWorldWritableDir_chmodFailure_logsWarn(@TempDir Path dir) throws Exception {
        Method m = ContainerExecutionService.class.getDeclaredMethod("trySetWorldWritableDir", Path.class);
        m.setAccessible(true);
        TestableExecutionService svc = new TestableExecutionService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository, mock(DockerClient.class));
        try (MockedStatic<Files> files = mockStatic(Files.class, Mockito.withSettings().defaultAnswer(Mockito.CALLS_REAL_METHODS))) {
            files.when(() -> Files.setPosixFilePermissions(any(Path.class), any()))
                    .thenThrow(new IOException("chmod fail"));
            m.invoke(svc, dir);
        }
    }

    @Test
    void stopContainer_stoppedStatus_skipsStop(@TempDir Path dir) throws Exception {
        Method m = ContainerExecutionService.class.getDeclaredMethod("stopContainer", DockerClient.class, String.class);
        m.setAccessible(true);
        DockerClient docker = mock(DockerClient.class);
        InspectContainerCmd inspectCmd = mock(InspectContainerCmd.class);
        when(docker.inspectContainerCmd("cid")).thenReturn(inspectCmd);
        InspectContainerResponse r = mock(InspectContainerResponse.class);
        InspectContainerResponse.ContainerState st = mock(InspectContainerResponse.ContainerState.class);
        when(st.getStatus()).thenReturn("stopped");
        when(r.getState()).thenReturn(st);
        when(inspectCmd.exec()).thenReturn(r);
        TestableExecutionService svc = new TestableExecutionService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository, mock(DockerClient.class));
        m.invoke(svc, docker, "cid");
        verify(docker, never()).stopContainerCmd(anyString());
    }

    @Test
    void stopContainer_alreadyExited_skipsStop(@TempDir Path dir) throws Exception {
        Method m = ContainerExecutionService.class.getDeclaredMethod("stopContainer", DockerClient.class, String.class);
        m.setAccessible(true);
        DockerClient docker = mock(DockerClient.class);
        InspectContainerCmd inspectCmd = mock(InspectContainerCmd.class);
        when(docker.inspectContainerCmd("cid")).thenReturn(inspectCmd);
        InspectContainerResponse exited = exitedInspect(0);
        when(inspectCmd.exec()).thenReturn(exited);
        TestableExecutionService svc = new TestableExecutionService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository, mock(DockerClient.class));
        m.invoke(svc, docker, "cid");
        verify(docker, never()).stopContainerCmd(anyString());
    }

    @Test
    void stopContainer_running_invokesStop(@TempDir Path dir) throws Exception {
        Method m = ContainerExecutionService.class.getDeclaredMethod("stopContainer", DockerClient.class, String.class);
        m.setAccessible(true);
        DockerClient docker = mock(DockerClient.class);
        InspectContainerCmd inspectCmd = mock(InspectContainerCmd.class);
        when(docker.inspectContainerCmd("cid")).thenReturn(inspectCmd);
        InspectContainerResponse running = runningInspect();
        when(inspectCmd.exec()).thenReturn(running);
        StopContainerCmd stopCmd = mock(StopContainerCmd.class);
        when(docker.stopContainerCmd("cid")).thenReturn(stopCmd);
        TestableExecutionService svc = new TestableExecutionService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository, mock(DockerClient.class));
        m.invoke(svc, docker, "cid");
        verify(stopCmd).exec();
    }

    @Test
    void stopContainer_unknownStatus_noStop(@TempDir Path dir) throws Exception {
        Method m = ContainerExecutionService.class.getDeclaredMethod("stopContainer", DockerClient.class, String.class);
        m.setAccessible(true);
        DockerClient docker = mock(DockerClient.class);
        InspectContainerCmd inspectCmd = mock(InspectContainerCmd.class);
        when(docker.inspectContainerCmd("cid")).thenReturn(inspectCmd);
        InspectContainerResponse r = mock(InspectContainerResponse.class);
        InspectContainerResponse.ContainerState st = mock(InspectContainerResponse.ContainerState.class);
        when(st.getStatus()).thenReturn("created");
        when(r.getState()).thenReturn(st);
        when(inspectCmd.exec()).thenReturn(r);
        TestableExecutionService svc = new TestableExecutionService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository, mock(DockerClient.class));
        m.invoke(svc, docker, "cid");
        verify(docker, never()).stopContainerCmd(anyString());
    }

    @Test
    void stopContainer_stopNotModified_swallowed(@TempDir Path dir) throws Exception {
        Method m = ContainerExecutionService.class.getDeclaredMethod("stopContainer", DockerClient.class, String.class);
        m.setAccessible(true);
        DockerClient docker = mock(DockerClient.class);
        InspectContainerCmd inspectCmd = mock(InspectContainerCmd.class);
        when(docker.inspectContainerCmd("cid")).thenReturn(inspectCmd);
        InspectContainerResponse running = runningInspect();
        when(inspectCmd.exec()).thenReturn(running);
        StopContainerCmd stopCmd = mock(StopContainerCmd.class);
        when(docker.stopContainerCmd("cid")).thenReturn(stopCmd);
        when(stopCmd.exec()).thenThrow(new NotModifiedException("already"));
        TestableExecutionService svc = new TestableExecutionService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository, mock(DockerClient.class));
        m.invoke(svc, docker, "cid");
    }

    @Test
    void stopContainer_stopNotFound_swallowed(@TempDir Path dir) throws Exception {
        Method m = ContainerExecutionService.class.getDeclaredMethod("stopContainer", DockerClient.class, String.class);
        m.setAccessible(true);
        DockerClient docker = mock(DockerClient.class);
        InspectContainerCmd inspectCmd = mock(InspectContainerCmd.class);
        when(docker.inspectContainerCmd("cid")).thenReturn(inspectCmd);
        InspectContainerResponse running = runningInspect();
        when(inspectCmd.exec()).thenReturn(running);
        StopContainerCmd stopCmd = mock(StopContainerCmd.class);
        when(docker.stopContainerCmd("cid")).thenReturn(stopCmd);
        when(stopCmd.exec()).thenThrow(new NotFoundException("gone"));
        TestableExecutionService svc = new TestableExecutionService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository, mock(DockerClient.class));
        m.invoke(svc, docker, "cid");
    }

    @Test
    void stopContainer_inspectFailure_throwsRuntime(@TempDir Path dir) throws Exception {
        Method m = ContainerExecutionService.class.getDeclaredMethod("stopContainer", DockerClient.class, String.class);
        m.setAccessible(true);
        DockerClient docker = mock(DockerClient.class);
        InspectContainerCmd inspectCmd = mock(InspectContainerCmd.class);
        when(docker.inspectContainerCmd("cid")).thenReturn(inspectCmd);
        when(inspectCmd.exec()).thenThrow(new RuntimeException("inspect"));
        TestableExecutionService svc = new TestableExecutionService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository, mock(DockerClient.class));
        assertThatThrownBy(() -> {
            try {
                m.invoke(svc, docker, "cid");
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }).isInstanceOf(RuntimeException.class).hasMessageContaining("Failed to stop");
    }

    @Test
    void removeContainer_notFound_swallowed(@TempDir Path dir) throws Exception {
        Method m = ContainerExecutionService.class.getDeclaredMethod("removeContainer", DockerClient.class, String.class);
        m.setAccessible(true);
        DockerClient docker = mock(DockerClient.class);
        RemoveContainerCmd removeCmd = mock(RemoveContainerCmd.class);
        when(docker.removeContainerCmd("cid")).thenReturn(removeCmd);
        when(removeCmd.exec()).thenThrow(new NotFoundException("gone"));
        TestableExecutionService svc = new TestableExecutionService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository, mock(DockerClient.class));
        m.invoke(svc, docker, "cid");
    }

    @Test
    void removeContainer_genericFailure_throws(@TempDir Path dir) throws Exception {
        Method m = ContainerExecutionService.class.getDeclaredMethod("removeContainer", DockerClient.class, String.class);
        m.setAccessible(true);
        DockerClient docker = mock(DockerClient.class);
        RemoveContainerCmd removeCmd = mock(RemoveContainerCmd.class);
        when(docker.removeContainerCmd("cid")).thenReturn(removeCmd);
        when(removeCmd.exec()).thenThrow(new RuntimeException("rm"));
        TestableExecutionService svc = new TestableExecutionService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository, mock(DockerClient.class));
        assertThatThrownBy(() -> {
            try {
                m.invoke(svc, docker, "cid");
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }).isInstanceOf(RuntimeException.class).hasMessageContaining("Failed to remove");
    }

    @Test
    void cleanupContainer_stopFailure_removeStillRuns(@TempDir Path dir) throws Exception {
        Method m = ContainerExecutionService.class.getDeclaredMethod("cleanupContainer", DockerClient.class, String.class);
        m.setAccessible(true);
        DockerClient docker = mock(DockerClient.class);
        InspectContainerCmd inspectCmd = mock(InspectContainerCmd.class);
        when(docker.inspectContainerCmd("cid")).thenReturn(inspectCmd);
        when(inspectCmd.exec()).thenThrow(new RuntimeException("inspect stop"));
        RemoveContainerCmd removeCmd = mock(RemoveContainerCmd.class);
        when(docker.removeContainerCmd("cid")).thenReturn(removeCmd);
        TestableExecutionService svc = new TestableExecutionService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository, mock(DockerClient.class));
        m.invoke(svc, docker, "cid");
        verify(removeCmd).exec();
    }

    @Test
    void cleanupContainer_removeFailure_swallowed(@TempDir Path dir) throws Exception {
        Method m = ContainerExecutionService.class.getDeclaredMethod("cleanupContainer", DockerClient.class, String.class);
        m.setAccessible(true);
        DockerClient docker = mock(DockerClient.class);
        InspectContainerCmd inspectCmd = mock(InspectContainerCmd.class);
        when(docker.inspectContainerCmd("cid")).thenReturn(inspectCmd);
        InspectContainerResponse exited = exitedInspect(0);
        when(inspectCmd.exec()).thenReturn(exited);
        RemoveContainerCmd removeCmd = mock(RemoveContainerCmd.class);
        when(docker.removeContainerCmd("cid")).thenReturn(removeCmd);
        when(removeCmd.exec()).thenThrow(new RuntimeException("remove fail"));
        TestableExecutionService svc = new TestableExecutionService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository, mock(DockerClient.class));
        m.invoke(svc, docker, "cid");
    }

    @Test
    void logContainerLogs_emptyOutput_warnsNoLogs(@TempDir Path dir) throws Exception {
        Method m = ContainerExecutionService.class.getDeclaredMethod(
                "logContainerLogs", DockerClient.class, String.class, String.class);
        m.setAccessible(true);
        DockerClient docker = mock(DockerClient.class);
        wireLogContainerEmpty(docker);
        TestableExecutionService svc = new TestableExecutionService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository, mock(DockerClient.class));
        String logs = (String) m.invoke(svc, docker, "cid", "cname");
        assertThat(logs).isEmpty();
    }

    @Test
    void logContainerLogs_traceback_logsLineByLine(@TempDir Path dir) throws Exception {
        Method m = ContainerExecutionService.class.getDeclaredMethod(
                "logContainerLogs", DockerClient.class, String.class, String.class);
        m.setAccessible(true);
        DockerClient docker = mock(DockerClient.class);
        wireLogContainerForTraceback(docker);
        TestableExecutionService svc = new TestableExecutionService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository, mock(DockerClient.class));
        String logs = (String) m.invoke(svc, docker, "cid", "cname");
        assertThat(logs).contains("Traceback");
    }

    @Test
    void logContainerLogs_traceback_withBlankLines_skipsBlankLinesInLoop(@TempDir Path dir) throws Exception {
        Method m = ContainerExecutionService.class.getDeclaredMethod(
                "logContainerLogs", DockerClient.class, String.class, String.class);
        m.setAccessible(true);
        DockerClient docker = mock(DockerClient.class);
        wireLogContainerTracebackWithBlankLines(docker);
        TestableExecutionService svc = new TestableExecutionService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository, mock(DockerClient.class));
        String logs = (String) m.invoke(svc, docker, "cid", "cname");
        assertThat(logs).contains("Traceback");
    }

    @Test
    void logContainerLogs_plainWithoutTraceback_singleErrorBlock(@TempDir Path dir) throws Exception {
        Method m = ContainerExecutionService.class.getDeclaredMethod(
                "logContainerLogs", DockerClient.class, String.class, String.class);
        m.setAccessible(true);
        DockerClient docker = mock(DockerClient.class);
        wireLogContainerPlain(docker, "err line only\n");
        TestableExecutionService svc = new TestableExecutionService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository, mock(DockerClient.class));
        String logs = (String) m.invoke(svc, docker, "cid", "cname");
        assertThat(logs).contains("err line");
    }

    @Test
    void logContainerLogs_execFailure_returnsNull(@TempDir Path dir) throws Exception {
        Method m = ContainerExecutionService.class.getDeclaredMethod(
                "logContainerLogs", DockerClient.class, String.class, String.class);
        m.setAccessible(true);
        DockerClient docker = mock(DockerClient.class);
        LogContainerCmd logCmd = mock(LogContainerCmd.class, RETURNS_SELF);
        when(docker.logContainerCmd(anyString())).thenReturn(logCmd);
        when(logCmd.exec(any(LogContainerResultCallback.class))).thenThrow(new RuntimeException("log fail"));
        TestableExecutionService svc = new TestableExecutionService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository, mock(DockerClient.class));
        assertThat(m.invoke(svc, docker, "cid", "cname")).isNull();
    }

    @Test
    void logContainerLogsForDebug_empty(@TempDir Path dir) throws Exception {
        Method m = ContainerExecutionService.class.getDeclaredMethod(
                "logContainerLogsForDebug", DockerClient.class, String.class, String.class);
        m.setAccessible(true);
        DockerClient docker = mock(DockerClient.class);
        wireLogContainerEmpty(docker);
        TestableExecutionService svc = new TestableExecutionService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository, mock(DockerClient.class));
        m.invoke(svc, docker, "cid", "cname");
    }

    @Test
    void logContainerLogsForDebug_nonEmpty_logsDebugBlock(@TempDir Path dir) throws Exception {
        Method m = ContainerExecutionService.class.getDeclaredMethod(
                "logContainerLogsForDebug", DockerClient.class, String.class, String.class);
        m.setAccessible(true);
        DockerClient docker = mock(DockerClient.class);
        wireLogContainerPlain(docker, "stats line\n");
        TestableExecutionService svc = new TestableExecutionService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository, mock(DockerClient.class));
        m.invoke(svc, docker, "cid", "cname");
    }

    @Test
    void logContainerLogsForDebug_execFailure_swallowed(@TempDir Path dir) throws Exception {
        Method m = ContainerExecutionService.class.getDeclaredMethod(
                "logContainerLogsForDebug", DockerClient.class, String.class, String.class);
        m.setAccessible(true);
        DockerClient docker = mock(DockerClient.class);
        LogContainerCmd logCmd = mock(LogContainerCmd.class, RETURNS_SELF);
        when(docker.logContainerCmd(anyString())).thenReturn(logCmd);
        when(logCmd.exec(any(LogContainerResultCallback.class))).thenThrow(new RuntimeException("dbg fail"));
        TestableExecutionService svc = new TestableExecutionService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository, mock(DockerClient.class));
        m.invoke(svc, docker, "cid", "cname");
    }

    @Test
    void ensureImageExists_pullFailure_wrapsRuntime(@TempDir Path dir) throws Exception {
        Method m = ContainerExecutionService.class.getDeclaredMethod("ensureImageExists", String.class, DockerClient.class);
        m.setAccessible(true);
        DockerClient docker = mock(DockerClient.class);
        InspectImageCmd imgCmd = mock(InspectImageCmd.class);
        when(docker.inspectImageCmd("img:x")).thenReturn(imgCmd);
        when(imgCmd.exec()).thenThrow(new NotFoundException("no"));
        PullImageCmd pullCmd = mock(PullImageCmd.class);
        PullImageResultCallback pullCb = mock(PullImageResultCallback.class);
        when(docker.pullImageCmd("img:x")).thenReturn(pullCmd);
        when(pullCmd.exec(any(PullImageResultCallback.class))).thenReturn(pullCb);
        when(pullCb.awaitCompletion()).thenThrow(new RuntimeException("pull fail"));
        TestableExecutionService svc = new TestableExecutionService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository, mock(DockerClient.class));
        assertThatThrownBy(() -> {
            try {
                m.invoke(svc, "img:x", docker);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }).isInstanceOf(RuntimeException.class).hasMessageContaining("Failed to pull Docker image");
    }

    private ExecutionRequest baseRequest(Path testFile, UUID profileId) {
        ExecutionRequest req = new ExecutionRequest();
        req.setTestTool("locust");
        req.setCommand("locust -f /mnt/test/{fileName}");
        req.setTestFilePath(testFile.toAbsolutePath().toString());
        req.setDockerExecutionProfileId(profileId);
        return req;
    }

    private void stubCommonCommandSideEffects(Path dir) {
        Path reports = dir.resolve("reports");
        Path metrics = dir.resolve("metrics");
        when(commandFromDbService.resolveArtifactPaths()).thenReturn(
                new CommandFromDbService.ArtifactPaths(
                        reports.toString(), metrics.toString(), "r", "m"));
        lenient().when(commandFromDbService.buildCommand(anyString(), any()))
                .thenReturn(List.of("locust", "-f", "/mnt/test/t.py"));
        lenient().when(commandFromDbService.buildBinds(any()))
                .thenReturn(List.of(Bind.parse(dir.toAbsolutePath() + ":/mnt/test:rw")));
        lenient().when(commandFromDbService.applyDockerProfile(any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private DockerClient mockHappyPathDocker() throws Exception {
        return mockHappyPathDockerWithContainerId(CONTAINER_ID);
    }

    private DockerClient mockHappyPathDockerWithContainerId(String containerId) throws Exception {
        DockerClient docker = mock(DockerClient.class);
        stubImagePullIfMissing(docker);
        CreateContainerCmd createCmd = mock(CreateContainerCmd.class, RETURNS_SELF);
        CreateContainerResponse created = mock(CreateContainerResponse.class);
        when(created.getId()).thenReturn(containerId);
        when(createCmd.exec()).thenReturn(created);
        when(docker.createContainerCmd(anyString())).thenReturn(createCmd);
        StartContainerCmd startCmd = mock(StartContainerCmd.class);
        when(docker.startContainerCmd(containerId)).thenReturn(startCmd);
        InspectContainerResponse postStartRunning = runningInspect();
        InspectContainerResponse named = namedInspect("/locust-test-xyz");
        AtomicInteger inspectPhase = new AtomicInteger();
        InspectContainerCmd inspectCmd = mock(InspectContainerCmd.class);
        when(docker.inspectContainerCmd(anyString())).thenReturn(inspectCmd);
        when(inspectCmd.exec()).thenAnswer(inv -> {
            int n = inspectPhase.getAndIncrement();
            int cycle = n % 3;
            if (cycle == 0) {
                return postStartRunning;
            }
            if (cycle == 1) {
                return named;
            }
            return postStartRunning;
        });
        WaitContainerCmd waitCmd = mock(WaitContainerCmd.class);
        when(docker.waitContainerCmd(containerId)).thenReturn(waitCmd);
        WaitResponse waitResponse = mock(WaitResponse.class);
        lenient().when(waitResponse.getStatusCode()).thenReturn(0);
        when(waitCmd.exec(any(WaitContainerResultCallback.class))).thenAnswer(inv -> {
            WaitContainerResultCallback cb = inv.getArgument(0);
            cb.onNext(waitResponse);
            cb.onComplete();
            return null;
        });
        wireLogContainerEmpty(docker);
        StopContainerCmd stopCmd = mock(StopContainerCmd.class);
        RemoveContainerCmd removeCmd = mock(RemoveContainerCmd.class);
        when(docker.stopContainerCmd(anyString())).thenReturn(stopCmd);
        when(docker.removeContainerCmd(anyString())).thenReturn(removeCmd);
        return docker;
    }

    private static void wireCreateStart(DockerClient docker) throws Exception {
        wireCreateStartWithId(docker, CONTAINER_ID);
    }

    private static void wireCreateStartWithId(DockerClient docker, String containerId) {
        CreateContainerCmd createCmd = mock(CreateContainerCmd.class, RETURNS_SELF);
        CreateContainerResponse created = mock(CreateContainerResponse.class);
        when(created.getId()).thenReturn(containerId);
        when(createCmd.exec()).thenReturn(created);
        when(docker.createContainerCmd(anyString())).thenReturn(createCmd);
        StartContainerCmd startCmd = mock(StartContainerCmd.class);
        when(docker.startContainerCmd(containerId)).thenReturn(startCmd);
    }

    private static void stubImagePresent(DockerClient docker) {
        InspectImageCmd inspectImageCmd = mock(InspectImageCmd.class);
        when(docker.inspectImageCmd(anyString())).thenReturn(inspectImageCmd);
        when(inspectImageCmd.exec()).thenReturn(mock(InspectImageResponse.class));
    }

    private static void stubImagePullIfMissing(DockerClient docker) throws Exception {
        InspectImageCmd inspectImageCmd = mock(InspectImageCmd.class);
        when(docker.inspectImageCmd(anyString())).thenReturn(inspectImageCmd);
        when(inspectImageCmd.exec()).thenThrow(new NotFoundException("missing"));
        PullImageCmd pullCmd = mock(PullImageCmd.class);
        PullImageResultCallback pullCb = mock(PullImageResultCallback.class);
        when(docker.pullImageCmd(anyString())).thenReturn(pullCmd);
        when(pullCmd.exec(any(PullImageResultCallback.class))).thenReturn(pullCb);
        when(pullCb.awaitCompletion()).thenReturn(null);
    }

    private static InspectContainerResponse runningInspect() {
        InspectContainerResponse r = mock(InspectContainerResponse.class);
        InspectContainerResponse.ContainerState st = mock(InspectContainerResponse.ContainerState.class);
        when(st.getStatus()).thenReturn("running");
        when(r.getState()).thenReturn(st);
        return r;
    }

    private static InspectContainerResponse exitedInspect(Integer exit) {
        InspectContainerResponse r = mock(InspectContainerResponse.class);
        InspectContainerResponse.ContainerState st = mock(InspectContainerResponse.ContainerState.class);
        when(st.getStatus()).thenReturn("exited");
        when(st.getExitCode()).thenReturn(exit);
        when(r.getState()).thenReturn(st);
        return r;
    }

    private static InspectContainerResponse namedInspect(String name) {
        InspectContainerResponse r = mock(InspectContainerResponse.class);
        when(r.getName()).thenReturn(name);
        InspectContainerResponse.ContainerState st = mock(InspectContainerResponse.ContainerState.class);
        when(st.getStatus()).thenReturn("running");
        when(r.getState()).thenReturn(st);
        return r;
    }

    private static void wireLogContainerEmpty(DockerClient docker) {
        LogContainerCmd logCmd = mock(LogContainerCmd.class, RETURNS_SELF);
        when(docker.logContainerCmd(anyString())).thenReturn(logCmd);
        when(logCmd.exec(any(ResultCallback.class))).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            ResultCallback<Frame> cb = inv.getArgument(0);
            cb.onComplete();
            return cb;
        });
    }

    private static void wireLogContainerPlain(DockerClient docker, String text) {
        LogContainerCmd logCmd = mock(LogContainerCmd.class, RETURNS_SELF);
        when(docker.logContainerCmd(anyString())).thenReturn(logCmd);
        when(logCmd.exec(any(LogContainerResultCallback.class))).thenAnswer(inv -> {
            LogContainerResultCallback cb = inv.getArgument(0);
            cb.onNext(new Frame(StreamType.STDOUT, text.getBytes(StandardCharsets.UTF_8)));
            cb.onComplete();
            return cb;
        });
    }

    private static void wireLogContainerForTraceback(DockerClient docker) {
        LogContainerCmd logCmd = mock(LogContainerCmd.class, RETURNS_SELF);
        when(docker.logContainerCmd(anyString())).thenReturn(logCmd);
        when(logCmd.exec(any(LogContainerResultCallback.class))).thenAnswer(inv -> {
            LogContainerResultCallback cb = inv.getArgument(0);
            String line = "Traceback (most recent call last)\n";
            cb.onNext(new Frame(StreamType.STDERR, line.getBytes(StandardCharsets.UTF_8)));
            cb.onComplete();
            return cb;
        });
    }

    private static void wireLogContainerTracebackWithBlankLines(DockerClient docker) {
        LogContainerCmd logCmd = mock(LogContainerCmd.class, RETURNS_SELF);
        when(docker.logContainerCmd(anyString())).thenReturn(logCmd);
        when(logCmd.exec(any(LogContainerResultCallback.class))).thenAnswer(inv -> {
            LogContainerResultCallback cb = inv.getArgument(0);
            String payload = "Traceback (most recent call last):\n\n  \t\n  File \"x.py\", line 1\n";
            cb.onNext(new Frame(StreamType.STDERR, payload.getBytes(StandardCharsets.UTF_8)));
            cb.onComplete();
            return cb;
        });
    }
}
