package com.loadtest.execution;

import com.github.dockerjava.api.DockerClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class ContainerExecutionServicePredicatesTest {

    @Mock
    private DockerClient dockerClient;

    @AfterEach
    void clearOsOverride() {
        ContainerExecutionService.clearDockerBuildFailureOsNameOverrideForTests();
    }

    @Test
    void isBlankDockerHostUri_null_or_blank_true() {
        assertThat(ContainerExecutionService.isBlankDockerHostUri(null)).isTrue();
        assertThat(ContainerExecutionService.isBlankDockerHostUri("")).isTrue();
        assertThat(ContainerExecutionService.isBlankDockerHostUri("  \t ")).isTrue();
    }

    @Test
    void isBlankDockerHostUri_nonBlank_false() {
        assertThat(ContainerExecutionService.isBlankDockerHostUri("tcp://127.0.0.1:2375")).isFalse();
    }

    @Test
    void shouldLogWindowsDockerDesktopHint_whenOsNameNull_returnsFalse() {
        assertThat(ContainerExecutionService.shouldLogWindowsDockerDesktopHint(null)).isFalse();
    }

    @Test
    void shouldLogWindowsDockerDesktopHint_win_true_else_false() {
        assertThat(ContainerExecutionService.shouldLogWindowsDockerDesktopHint("Windows 11")).isTrue();
        assertThat(ContainerExecutionService.shouldLogWindowsDockerDesktopHint("microsoft windows")).isTrue();
        assertThat(ContainerExecutionService.shouldLogWindowsDockerDesktopHint("Linux")).isFalse();
        assertThat(ContainerExecutionService.shouldLogWindowsDockerDesktopHint("Mac OS X")).isFalse();
    }

    @Test
    void dockerBuildFailureOsHint_whenOverrideNonWindows_hintConditionFalse() {
        ContainerExecutionService.setDockerBuildFailureOsNameOverrideForTests();
        assertThat(ContainerExecutionService.shouldLogWindowsDockerDesktopHint(
                ContainerExecutionService.dockerBuildFailureOsNameForHint())).isFalse();
    }

    @Test
    void stripLeadingSlashFromInspectName_withSlash_strips() {
        assertThat(ContainerExecutionService.stripLeadingSlashFromInspectName("/locust-test-1"))
                .isEqualTo("locust-test-1");
    }

    @Test
    void stripLeadingSlashFromInspectName_withoutSlash_unchanged() {
        assertThat(ContainerExecutionService.stripLeadingSlashFromInspectName("locust-test-1"))
                .isEqualTo("locust-test-1");
    }

    @Test
    void shouldParseProfileEnvironmentVariables_null_or_blank_false() {
        assertThat(ContainerExecutionService.shouldParseProfileEnvironmentVariables(null)).isFalse();
        assertThat(ContainerExecutionService.shouldParseProfileEnvironmentVariables("  \t ")).isFalse();
    }

    @Test
    void shouldParseProfileEnvironmentVariables_nonBlank_true() {
        assertThat(ContainerExecutionService.shouldParseProfileEnvironmentVariables("{}")).isTrue();
        assertThat(ContainerExecutionService.shouldParseProfileEnvironmentVariables("{\"a\":1}")).isTrue();
    }

    @Test
    void immediateExitHasTraceback_null_or_plain_false() {
        assertThat(ContainerExecutionService.immediateExitHasTraceback(null)).isFalse();
        assertThat(ContainerExecutionService.immediateExitHasTraceback("no traceback")).isFalse();
    }

    @Test
    void immediateExitHasTraceback_contains_true() {
        assertThat(ContainerExecutionService.immediateExitHasTraceback("Traceback (most recent call last)")).isTrue();
    }

    @Test
    void immediateExitLogAppendix_null_empty_else_value() {
        assertThat(ContainerExecutionService.immediateExitLogAppendix(null)).isEmpty();
        assertThat(ContainerExecutionService.immediateExitLogAppendix("err")).isEqualTo("err");
    }

    @Test
    void hasNonEmptyInspectContainerName_null_or_empty_false() {
        assertThat(ContainerExecutionService.hasNonEmptyInspectContainerName(null)).isFalse();
        assertThat(ContainerExecutionService.hasNonEmptyInspectContainerName("")).isFalse();
    }

    @Test
    void hasNonEmptyInspectContainerName_nonEmpty_true() {
        assertThat(ContainerExecutionService.hasNonEmptyInspectContainerName("/c-1")).isTrue();
    }

    @Test
    void shouldUseContainerIdPrefixAsDisplayName_lengthAtMost12_false() {
        assertThat(ContainerExecutionService.shouldUseContainerIdPrefixAsDisplayName(null)).isFalse();
        assertThat(ContainerExecutionService.shouldUseContainerIdPrefixAsDisplayName("")).isFalse();
        assertThat(ContainerExecutionService.shouldUseContainerIdPrefixAsDisplayName("abc123456789")).isFalse();
    }

    @Test
    void shouldUseContainerIdPrefixAsDisplayName_longId_true() {
        assertThat(ContainerExecutionService.shouldUseContainerIdPrefixAsDisplayName("abc12345678901")).isTrue();
    }

    @Test
    void shouldCollectArtifactsAfterRun_branches() {
        UUID tid = UUID.randomUUID();
        assertThat(ContainerExecutionService.shouldCollectArtifactsAfterRun(null, "run")).isFalse();
        assertThat(ContainerExecutionService.shouldCollectArtifactsAfterRun(tid, null)).isFalse();
        assertThat(ContainerExecutionService.shouldCollectArtifactsAfterRun(tid, "  ")).isFalse();
        assertThat(ContainerExecutionService.shouldCollectArtifactsAfterRun(tid, "locust -f x")).isTrue();
    }

    @Test
    void shouldCleanupAfterRuntimeFailure_branches() {
        assertThat(ContainerExecutionService.shouldCleanupAfterRuntimeFailure(null, null)).isFalse();
        assertThat(ContainerExecutionService.shouldCleanupAfterRuntimeFailure("cid", null)).isFalse();
        assertThat(ContainerExecutionService.shouldCleanupAfterRuntimeFailure(null, dockerClient)).isFalse();
        assertThat(ContainerExecutionService.shouldCleanupAfterRuntimeFailure("cid", mock(DockerClient.class)))
                .isTrue();
    }

    @Test
    void shouldEmitContainerTracebackDetailLine_blank_false() {
        assertThat(ContainerExecutionService.shouldEmitContainerTracebackDetailLine("")).isFalse();
        assertThat(ContainerExecutionService.shouldEmitContainerTracebackDetailLine("  \t ")).isFalse();
    }

    @Test
    void shouldEmitContainerTracebackDetailLine_nonBlank_true() {
        assertThat(ContainerExecutionService.shouldEmitContainerTracebackDetailLine("  File \"x.py\", line 1"))
                .isTrue();
    }
}
