package com.loadtest.execution.service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.model.Container;
import com.loadtest.execution.ContainerExecutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExitedLoadtestContainerJanitorTest {

    @Mock
    private ContainerExecutionService containerExecutionService;
    @Mock
    private DockerClient docker;
    @Mock
    private ListContainersCmd listContainersCmd;
    @Mock
    private InspectContainerCmd inspectContainerCmd;
    @Mock
    private RemoveContainerCmd removeContainerCmd;

    private ExitedLoadTestContainerJanitor janitor;

    @BeforeEach
    void setUp() {
        janitor = new ExitedLoadTestContainerJanitor(containerExecutionService);
        ReflectionTestUtils.setField(janitor, "retentionHours", 48L);
    }

    @Test
    void cleanupExpiredExitedContainers_removesOnlyOldExited() {
        OffsetDateTime now = OffsetDateTime.now();
        Container young = container("young", "exited");
        Container running = container("running", "running");
        Container old = container("old", "exited");

        doAnswer(invocation -> {
            java.util.function.Consumer<DockerClient> consumer = invocation.getArgument(0);
            consumer.accept(docker);
            return null;
        }).when(containerExecutionService).forEachDistinctDockerClient(any());
        when(docker.listContainersCmd()).thenReturn(listContainersCmd);
        when(listContainersCmd.withShowAll(true)).thenReturn(listContainersCmd);
        when(listContainersCmd.withLabelFilter(anyMap())).thenReturn(listContainersCmd);
        when(listContainersCmd.exec()).thenReturn(List.of(young, running, old));

        InspectContainerResponse youngInspect = inspect(now.minusHours(24).toString(), "exited");
        InspectContainerResponse oldInspect = inspect(now.minusHours(72).toString(), "exited");

        when(docker.inspectContainerCmd("young")).thenReturn(inspectContainerCmd);
        when(docker.inspectContainerCmd("old")).thenReturn(inspectContainerCmd);
        when(inspectContainerCmd.exec()).thenReturn(youngInspect, oldInspect);
        when(docker.removeContainerCmd("old")).thenReturn(removeContainerCmd);

        janitor.cleanupExpiredExitedContainers();

        verify(docker).removeContainerCmd("old");
        verify(docker, never()).removeContainerCmd("young");
        verify(docker, never()).removeContainerCmd("running");
    }

    private static Container container(String id, String state) {
        Container container = org.mockito.Mockito.mock(Container.class);
        when(container.getId()).thenReturn(id);
        when(container.getState()).thenReturn(state);
        return container;
    }

    private static InspectContainerResponse inspect(String finishedAt, String status) {
        InspectContainerResponse response = org.mockito.Mockito.mock(InspectContainerResponse.class);
        InspectContainerResponse.ContainerState state = org.mockito.Mockito.mock(InspectContainerResponse.ContainerState.class);
        when(response.getState()).thenReturn(state);
        when(state.getStatus()).thenReturn(status);
        when(state.getFinishedAt()).thenReturn(finishedAt);
        when(response.getName()).thenReturn("/k6-test-old");
        return response;
    }
}
