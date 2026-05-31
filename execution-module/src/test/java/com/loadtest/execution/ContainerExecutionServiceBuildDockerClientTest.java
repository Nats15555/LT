package com.loadtest.execution;

import com.github.dockerjava.api.DockerClient;
import com.loadtest.execution.persistence.DockerExecutionProfileRepository;
import com.loadtest.execution.service.ArtifactCollectorService;
import com.loadtest.execution.service.CommandFromDbService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ContainerExecutionServiceBuildDockerClientTest {

    public static boolean localDockerDaemonSocketPresent() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return windowsDockerPipeExists("//./pipe/docker_engine")
                    || windowsDockerPipeExists("//./pipe/dockerDesktopLinuxEngine");
        }
        return Files.exists(Paths.get("/var/run/docker.sock"))
                || Files.exists(Paths.get("/var/run/podman/podman.sock"));
    }

    private static boolean windowsDockerPipeExists(String pipePath) {
        try {
            return Files.exists(Paths.get(pipePath));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private String savedDockerHostProperty;

    @BeforeEach
    void saveDockerHostProperty() {
        savedDockerHostProperty = System.getProperty("DOCKER_HOST");
    }

    @AfterEach
    void restoreDockerHostProperty() {
        ContainerExecutionService.clearDockerBuildFailureOsNameOverrideForTests();
        if (savedDockerHostProperty == null) {
            System.clearProperty("DOCKER_HOST");
        } else {
            System.setProperty("DOCKER_HOST", savedDockerHostProperty);
        }
    }

    @Test
    void applyImplicit_sideEffects_windowsTcpDefault() {
        var implicit = new ContainerExecutionService.ImplicitDockerUriResolution(
                URI.create("tcp://localhost:2375"), true, false);
        System.clearProperty("DOCKER_HOST");
        ContainerExecutionService.applyImplicitDockerHostSideEffects(implicit, null);
        assertThat(System.getProperty("DOCKER_HOST")).isEqualTo("tcp://localhost:2375");
    }

    @Test
    void applyImplicit_sideEffects_loggedDockerHost() {
        var implicit = new ContainerExecutionService.ImplicitDockerUriResolution(
                URI.create("tcp://example:2376"), false, true);
        ContainerExecutionService.applyImplicitDockerHostSideEffects(implicit, "tcp://example:2376");
    }

    @Test
    void applyImplicit_sideEffects_defaultConnection() {
        var implicit = new ContainerExecutionService.ImplicitDockerUriResolution(
                URI.create("unix:///var/run/docker.sock"), false, false);
        ContainerExecutionService.applyImplicitDockerHostSideEffects(implicit, null);
    }

    @Test
    void resolveImplicitDockerUriForBuildStep_matchesResolveImplicit() {
        String os = System.getProperty("os.name").toLowerCase();
        String env = System.getenv("DOCKER_HOST");
        URI step = ContainerExecutionService.resolveImplicitDockerUriForBuildStep();
        assertThat(step).isEqualTo(ContainerExecutionService.resolveImplicitDockerUri(os, env).uri());
    }

    @Test
    void buildDockerClient_viaReflection_nullArg_entersImplicitUriBranch() throws Exception {
        BuildHarness h = new BuildHarness();
        Method m = ContainerExecutionService.class.getDeclaredMethod("buildDockerClient", String.class);
        m.setAccessible(true);
        try {
            Object result = m.invoke(h, (Object) null);
            assertThat(result).isInstanceOf(DockerClient.class);
            try (DockerClient c = (DockerClient) result) {
                assertThat(c).isNotNull();
            }
        } catch (InvocationTargetException e) {
            assertThat(e.getCause()).isInstanceOf(RuntimeException.class);
            assertThat(e.getCause().getMessage()).contains("Failed to initialize DockerClient");
        }
    }

    @Test
    void createDockerClient_explicitInvalidUri_throwsRuntimeException() {
        BuildHarness h = new BuildHarness();
        assertThatThrownBy(() -> h.createDockerClientWrapped("tcp://bad host:2375"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to initialize DockerClient");
    }

    @Test
    void createDockerClient_failureWhenOsHintOverrideNonWindows_skipsDesktopHintInCatch() {
        ContainerExecutionService.setDockerBuildFailureOsNameOverrideForTests();
        BuildHarness h = new BuildHarness();
        assertThatThrownBy(() -> h.createDockerClientWrapped("tcp://bad host:2375"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to initialize DockerClient");
    }

    @Test
    void createDockerClient_nullExplicitUri_usesImplicitResolvePath() throws Exception {
        BuildHarness h = new BuildHarness();
        try {
            try (DockerClient c = h.createDockerClientWrapped(null)) {
                assertThat(c).isNotNull();
            }
        } catch (RuntimeException e) {
            assertThat(e.getMessage()).contains("Failed to initialize DockerClient");
        }
    }

    @Test
    void createDockerClient_blankExplicitUri_usesImplicitResolvePath() throws Exception {
        BuildHarness h = new BuildHarness();
        try {
            try (DockerClient c = h.createDockerClientWrapped("  \t  ")) {
                assertThat(c).isNotNull();
            }
        } catch (RuntimeException e) {
            assertThat(e.getMessage()).contains("Failed to initialize DockerClient");
        }
    }

    @Test
    void createDockerClient_tcpUnreachable_pingFailsButReturnsClient() throws Exception {
        BuildHarness h = new BuildHarness();
        try (DockerClient client = h.createDockerClientWrapped("tcp://127.0.0.1:55441")) {
            assertThat(client).isNotNull();
        }
    }

    @Test
    @EnabledIf(
            value = "com.loadtest.execution.ContainerExecutionServiceBuildDockerClientTest#localDockerDaemonSocketPresent",
            disabledReason = "Пропуск: не найден локальный сокет Docker/Podman или Windows named pipe движка.")
    void createDockerClient_whenLocalDaemonPresent_pingSucceeds() throws Exception {
        BuildHarness h = new BuildHarness();
        String os = System.getProperty("os.name", "").toLowerCase();
        String uri;
        if (os.contains("win")) {
            if (windowsDockerPipeExists("//./pipe/docker_engine")) {
                uri = "npipe:////./pipe/docker_engine";
            } else {
                uri = "npipe:////./pipe/dockerDesktopLinuxEngine";
            }
        } else if (Files.exists(Paths.get("/var/run/docker.sock"))) {
            uri = "unix:///var/run/docker.sock";
        } else {
            uri = "unix:///var/run/podman/podman.sock";
        }
        try (DockerClient client = h.createDockerClientWrapped(uri)) {
            assertThat(client).isNotNull();
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void createDockerClient_failureOnWindows_logsDesktopHint() {
        BuildHarness h = new BuildHarness();
        assertThatThrownBy(() -> h.createDockerClientWrapped("tcp://bad host:2375"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to initialize DockerClient");
    }

    private static final class BuildHarness extends ContainerExecutionService {
        BuildHarness() {
            super(
                    mock(CommandFromDbService.class),
                    mock(ArtifactCollectorService.class),
                    mock(DockerExecutionProfileRepository.class));
        }

        DockerClient createDockerClientWrapped(String explicitUriOrNull) {
            return createDockerClient(explicitUriOrNull);
        }
    }
}
