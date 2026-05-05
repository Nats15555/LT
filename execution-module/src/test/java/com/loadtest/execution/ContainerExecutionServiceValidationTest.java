package com.loadtest.execution;

import com.loadtest.execution.dto.ExecutionRequest;
import com.loadtest.execution.persistence.DockerExecutionProfileRepository;
import com.loadtest.execution.persistence.LoadTestToolEntity;
import com.loadtest.execution.service.ArtifactCollectorService;
import com.loadtest.execution.service.CommandFromDbService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContainerExecutionServiceValidationTest {

    @Mock private CommandFromDbService commandFromDbService;
    @Mock private ArtifactCollectorService artifactCollectorService;
    @Mock private DockerExecutionProfileRepository dockerExecutionProfileRepository;

    @Test
    void rejectsBlankCommand(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("t.py");
        Files.writeString(f, "x");
        ContainerExecutionService svc = new ContainerExecutionService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository);
        ExecutionRequest req = new ExecutionRequest();
        req.setCommand("  ");
        req.setTestFilePath(f.toAbsolutePath().toString());
        assertThatThrownBy(() -> svc.executeTestWithAutoCleanup(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Command");
    }

    @Test
    void rejectsNullCommand(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("t.py");
        Files.writeString(f, "x");
        ContainerExecutionService svc = new ContainerExecutionService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository);
        ExecutionRequest req = new ExecutionRequest();
        req.setCommand(null);
        req.setTestFilePath(f.toAbsolutePath().toString());
        assertThatThrownBy(() -> svc.executeTestWithAutoCleanup(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Command");
    }

    @Test
    void rejectsBlankTestFilePath() {
        ContainerExecutionService svc = new ContainerExecutionService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository);
        ExecutionRequest req = new ExecutionRequest();
        req.setCommand("run");
        req.setTestFilePath(" ");
        assertThatThrownBy(() -> svc.executeTestWithAutoCleanup(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Test file path");
    }

    @Test
    void rejectsNullTestFilePath() {
        ContainerExecutionService svc = new ContainerExecutionService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository);
        ExecutionRequest req = new ExecutionRequest();
        req.setCommand("run");
        req.setTestFilePath(null);
        assertThatThrownBy(() -> svc.executeTestWithAutoCleanup(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Test file path");
    }

    @Test
    void rejectsMissingTestFile(@TempDir Path dir) {
        Path missing = dir.resolve("nope.py");
        ContainerExecutionService svc = new ContainerExecutionService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository);
        ExecutionRequest req = new ExecutionRequest();
        req.setCommand("run");
        req.setTestFilePath(missing.toAbsolutePath().toString());
        assertThatThrownBy(() -> svc.executeTestWithAutoCleanup(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void rejectsBlankTool(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("t.py");
        Files.writeString(f, "x");
        ContainerExecutionService svc = new ContainerExecutionService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository);
        ExecutionRequest req = new ExecutionRequest();
        req.setCommand("run");
        req.setTestFilePath(f.toAbsolutePath().toString());
        req.setTestTool("  ");
        assertThatThrownBy(() -> svc.executeTestWithAutoCleanup(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tool");
    }

    @Test
    void rejectsNullTestTool(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("t.py");
        Files.writeString(f, "x");
        ContainerExecutionService svc = new ContainerExecutionService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository);
        ExecutionRequest req = new ExecutionRequest();
        req.setCommand("run");
        req.setTestFilePath(f.toAbsolutePath().toString());
        req.setTestTool(null);
        assertThatThrownBy(() -> svc.executeTestWithAutoCleanup(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tool");
    }

    @Test
    void rejectsUnknownTool(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("t.py");
        Files.writeString(f, "x");
        when(commandFromDbService.getToolByName("UNKNOWN")).thenReturn(Optional.empty());
        ContainerExecutionService svc = new ContainerExecutionService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository);
        ExecutionRequest req = new ExecutionRequest();
        req.setCommand("run");
        req.setTestFilePath(f.toAbsolutePath().toString());
        req.setTestTool("unknown");
        assertThatThrownBy(() -> svc.executeTestWithAutoCleanup(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown");
    }

    @Test
    void rejectsNullDockerProfileId(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("t.py");
        Files.writeString(f, "x");
        LoadTestToolEntity tool = new LoadTestToolEntity();
        tool.setDockerImage("locustio/locust:latest");
        when(commandFromDbService.getToolByName("LOCUST")).thenReturn(Optional.of(tool));
        ContainerExecutionService svc = new ContainerExecutionService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository);
        ExecutionRequest req = new ExecutionRequest();
        req.setCommand("run");
        req.setTestFilePath(f.toAbsolutePath().toString());
        req.setTestTool("locust");
        req.setDockerExecutionProfileId(null);
        assertThatThrownBy(() -> svc.executeTestWithAutoCleanup(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dockerExecutionProfileId");
    }

    @Test
    void rejectsMissingDockerProfile(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("t.py");
        Files.writeString(f, "x");
        UUID profileId = UUID.randomUUID();
        LoadTestToolEntity tool = new LoadTestToolEntity();
        tool.setDockerImage("locustio/locust:latest");
        when(commandFromDbService.getToolByName("LOCUST")).thenReturn(Optional.of(tool));
        when(dockerExecutionProfileRepository.findByIdAndEnabledTrue(profileId)).thenReturn(Optional.empty());
        ContainerExecutionService svc = new ContainerExecutionService(
                commandFromDbService, artifactCollectorService, dockerExecutionProfileRepository);
        ExecutionRequest req = new ExecutionRequest();
        req.setCommand("run");
        req.setTestFilePath(f.toAbsolutePath().toString());
        req.setTestTool("locust");
        req.setDockerExecutionProfileId(profileId);
        assertThatThrownBy(() -> svc.executeTestWithAutoCleanup(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Docker profile");
    }
}
