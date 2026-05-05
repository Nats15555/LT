package com.loadtest.execution.service;

import com.loadtest.execution.ContainerExecutionService;
import com.loadtest.execution.dto.ExecutionRequest;
import com.loadtest.execution.dto.ExecutionResponse;
import com.loadtest.execution.dto.TestTaskMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.mockito.MockedStatic;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TestTaskProcessorTest {

    @TempDir java.nio.file.Path uploadRoot;

    @Mock private ContainerExecutionService executionService;

    private TestTaskProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new TestTaskProcessor(executionService);
        ReflectionTestUtils.setField(processor, "uploadDir", uploadRoot.toString());
    }

    @Test
    void rejectsMissingFileName() {
        TestTaskMessage msg = new TestTaskMessage();
        msg.setTaskId(UUID.randomUUID().toString());
        msg.setTestFileContent(Base64.getEncoder().encodeToString("a".getBytes(StandardCharsets.UTF_8)));
        msg.setExpectedDurationSeconds(10);
        assertThatThrownBy(() -> processor.process(msg)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("file name");
    }

    @Test
    void rejectsWhitespaceOnlyFileName() {
        TestTaskMessage msg = new TestTaskMessage();
        msg.setTaskId(UUID.randomUUID().toString());
        msg.setTestFileName("   ");
        msg.setTestFileContent(Base64.getEncoder().encodeToString("a".getBytes(StandardCharsets.UTF_8)));
        msg.setExpectedDurationSeconds(10);
        assertThatThrownBy(() -> processor.process(msg)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("file name");
    }

    @Test
    void rejectsMissingFileContent() {
        TestTaskMessage msg = new TestTaskMessage();
        msg.setTaskId(UUID.randomUUID().toString());
        msg.setTestFileName("t.py");
        msg.setExpectedDurationSeconds(10);
        assertThatThrownBy(() -> processor.process(msg)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("content");
    }

    @Test
    void rejectsWhitespaceOnlyFileContent() {
        TestTaskMessage msg = new TestTaskMessage();
        msg.setTaskId(UUID.randomUUID().toString());
        msg.setTestFileName("t.py");
        msg.setTestFileContent("  \t  ");
        msg.setExpectedDurationSeconds(10);
        assertThatThrownBy(() -> processor.process(msg)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("content");
    }

    @Test
    void rejectsInvalidDuration() {
        TestTaskMessage msg = new TestTaskMessage();
        msg.setTaskId(UUID.randomUUID().toString());
        msg.setTestFileName("t.py");
        msg.setTestFileContent(Base64.getEncoder().encodeToString("a".getBytes(StandardCharsets.UTF_8)));
        msg.setExpectedDurationSeconds(0);
        assertThatThrownBy(() -> processor.process(msg)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expectedDurationSeconds");
    }

    @Test
    void rejectsMissingDockerProfileId() throws Exception {
        TestTaskMessage msg = new TestTaskMessage();
        msg.setTaskId(UUID.randomUUID().toString());
        msg.setTestFileName("t.py");
        msg.setTestFileContent(Base64.getEncoder().encodeToString("a".getBytes(StandardCharsets.UTF_8)));
        msg.setExpectedDurationSeconds(5);
        msg.setCommand("run");
        msg.setTestTool("k6");
        assertThatThrownBy(() -> processor.process(msg)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dockerExecutionProfileId");
    }

    @Test
    void rejectsBlankDockerProfileId() {
        TestTaskMessage msg = baseValidMessage();
        msg.setDockerExecutionProfileId("   ");
        assertThatThrownBy(() -> processor.process(msg)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dockerExecutionProfileId");
    }

    @Test
    void rejectsNullExpectedDurationSeconds() {
        TestTaskMessage msg = baseValidMessage();
        msg.setExpectedDurationSeconds(null);
        assertThatThrownBy(() -> processor.process(msg)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expectedDurationSeconds");
    }

    @Test
    void success_withMetricsConfigConfiguredBranch() throws Exception {
        TestTaskMessage msg = baseValidMessage();
        TestTaskMessage.MetricsConfig cfg = new TestTaskMessage.MetricsConfig();
        cfg.setDelaySeconds(2);
        cfg.setRequests(List.of(new TestTaskMessage.MetricsConfig.MetricsRequest("m", "GET", "http://x", null, null, null)));
        msg.setMetricsConfig(cfg);
        when(executionService.executeTestWithAutoCleanup(any()))
                .thenReturn(ExecutionResponse.builder().status("success").executionTime(1L).build());
        assertThat(processor.process(msg)).isNotNull();
        verify(executionService).executeTestWithAutoCleanup(any());
    }

    @Test
    void success_metricsConfigPresent_requestsNull_logsRequestsAsZero() throws Exception {
        TestTaskMessage msg = baseValidMessage();
        TestTaskMessage.MetricsConfig cfg = new TestTaskMessage.MetricsConfig();
        cfg.setDelaySeconds(1);
        cfg.setRequests(null);
        msg.setMetricsConfig(cfg);
        when(executionService.executeTestWithAutoCleanup(any()))
                .thenReturn(ExecutionResponse.builder().status("success").executionTime(1L).build());
        assertThat(processor.process(msg)).isNotNull();
        verify(executionService).executeTestWithAutoCleanup(any());
    }

    @Test
    void success_metricsConfigAbsent_logsElseBranch() throws Exception {
        TestTaskMessage msg = baseValidMessage();
        msg.setMetricsConfig(null);
        when(executionService.executeTestWithAutoCleanup(any()))
                .thenReturn(ExecutionResponse.builder().status("success").executionTime(1L).build());
        assertThat(processor.process(msg)).isNotNull();
    }

    @Test
    void success_createsUploadDirectoryWhenAbsent(@TempDir java.nio.file.Path parent) throws Exception {
        java.nio.file.Path nestedUpload = parent.resolve("nested-upload");
        ReflectionTestUtils.setField(processor, "uploadDir", nestedUpload.toString());
        TestTaskMessage msg = baseValidMessage();
        when(executionService.executeTestWithAutoCleanup(any()))
                .thenReturn(ExecutionResponse.builder().status("success").executionTime(1L).build());
        assertThat(processor.process(msg)).isNotNull();
        assertThat(java.nio.file.Files.isDirectory(nestedUpload)).isTrue();
    }

    @Test
    void shouldDeleteTemporaryTestFile_null_shortCircuitsWithoutCallingExists() {
        try (MockedStatic<Files> files = Mockito.mockStatic(Files.class)) {
            assertThat(TestTaskProcessor.shouldDeleteTemporaryTestFile(null)).isFalse();
            files.verify(() -> Files.exists(any()), never());
        }
    }

    @Test
    void shouldDeleteTemporaryTestFile_whenPathMissing_returnsFalse(@TempDir Path dir) {
        assertThat(TestTaskProcessor.shouldDeleteTemporaryTestFile(dir.resolve("missing.txt"))).isFalse();
    }

    @Test
    void shouldDeleteTemporaryTestFile_whenFilePresent_returnsTrue(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("present.txt");
        Files.writeString(f, "x");
        assertThat(TestTaskProcessor.shouldDeleteTemporaryTestFile(f)).isTrue();
    }

    private TestTaskMessage baseValidMessage() {
        UUID taskId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        TestTaskMessage msg = new TestTaskMessage();
        msg.setTaskId(taskId.toString());
        msg.setTestFileName("t.py");
        msg.setTestFileContent(Base64.getEncoder().encodeToString("a".getBytes(StandardCharsets.UTF_8)));
        msg.setExpectedDurationSeconds(10);
        msg.setCommand("run");
        msg.setTestTool("k6");
        msg.setDockerExecutionProfileId(profileId.toString());
        return msg;
    }

    @Test
    void finally_warnsWhenTempFileDeleteFails() throws Exception {
        try (MockedStatic<Files> files = Mockito.mockStatic(Files.class, Mockito.withSettings().defaultAnswer(Mockito.CALLS_REAL_METHODS))) {
            files.when(() -> Files.delete(any(Path.class))).thenThrow(new IOException("delete blocked"));
            TestTaskMessage msg = baseValidMessage();
            when(executionService.executeTestWithAutoCleanup(any()))
                    .thenReturn(ExecutionResponse.builder().status("success").executionTime(1L).build());
            assertThat(processor.process(msg)).isNotNull();
            verify(executionService).executeTestWithAutoCleanup(any());
        }
    }

    @Test
    void finally_onExecuteFailure_whenDeleteThrows_logsWarnOnExceptionHandlerPath() {
        try (MockedStatic<Files> files = Mockito.mockStatic(Files.class, Mockito.withSettings().defaultAnswer(Mockito.CALLS_REAL_METHODS))) {
            files.when(() -> Files.delete(any(Path.class))).thenThrow(new IOException("delete blocked"));
            TestTaskMessage msg = baseValidMessage();
            when(executionService.executeTestWithAutoCleanup(any())).thenThrow(new RuntimeException("run failed"));
            assertThatThrownBy(() -> processor.process(msg)).isInstanceOf(RuntimeException.class).hasMessageContaining("run failed");
            verify(executionService).executeTestWithAutoCleanup(any());
        }
    }

    @Test
    void finally_onExecuteFailure_whenDeleteSucceeds_innerCatchNotTaken() throws Exception {
        TestTaskMessage msg = baseValidMessage();
        Path saved = Paths.get(uploadRoot.toString()).resolve(msg.getTestFileName());
        when(executionService.executeTestWithAutoCleanup(any())).thenThrow(new RuntimeException("run failed"));
        assertThatThrownBy(() -> processor.process(msg)).isInstanceOf(RuntimeException.class).hasMessageContaining("run failed");
        assertThat(Files.exists(saved)).isFalse();
    }

    @Test
    void finally_onExecuteFailure_whenFileAlreadyMissing_skipsDeleteOnExceptionHandlerPath() throws Exception {
        TestTaskMessage msg = baseValidMessage();
        String dir = (String) ReflectionTestUtils.getField(processor, "uploadDir");
        Path saved = Paths.get(dir).resolve(msg.getTestFileName());
        try (MockedStatic<Files> files = Mockito.mockStatic(Files.class, Mockito.withSettings().defaultAnswer(Mockito.CALLS_REAL_METHODS))) {
            files.when(() -> Files.exists(any(Path.class))).thenAnswer(invocation -> {
                Path p = invocation.getArgument(0);
                if (p.equals(saved)) {
                    return false;
                }
                return p.toFile().exists();
            });
            when(executionService.executeTestWithAutoCleanup(any())).thenThrow(new RuntimeException("run failed"));
            assertThatThrownBy(() -> processor.process(msg)).isInstanceOf(RuntimeException.class).hasMessageContaining("run failed");
        }
        assertThat(Files.exists(saved)).isTrue();
    }

    @Test
    void finally_skipsCleanupWhenSavedFileAlreadyMissing() throws Exception {
        TestTaskMessage msg = baseValidMessage();
        String dir = (String) ReflectionTestUtils.getField(processor, "uploadDir");
        Path saved = Paths.get(dir).resolve(msg.getTestFileName());
        try (MockedStatic<Files> files = Mockito.mockStatic(Files.class, Mockito.withSettings().defaultAnswer(Mockito.CALLS_REAL_METHODS))) {
            files.when(() -> Files.exists(any(Path.class))).thenAnswer(invocation -> {
                Path p = invocation.getArgument(0);
                if (p.equals(saved)) {
                    return false;
                }
                return p.toFile().exists();
            });
            when(executionService.executeTestWithAutoCleanup(any()))
                    .thenReturn(ExecutionResponse.builder().status("success").executionTime(1L).build());
            assertThat(processor.process(msg)).isNotNull();
        }
        assertThat(Files.exists(saved)).isTrue();
    }

    @Test
    void finally_filesExistsThrowsAfterSuccess_propagatesFromFinally() throws Exception {
        TestTaskMessage msg = baseValidMessage();
        String dir = (String) ReflectionTestUtils.getField(processor, "uploadDir");
        Path saved = Paths.get(dir).resolve(msg.getTestFileName());
        try (MockedStatic<Files> files = Mockito.mockStatic(Files.class, Mockito.withSettings().defaultAnswer(Mockito.CALLS_REAL_METHODS))) {
            files.when(() -> Files.exists(any(Path.class))).thenAnswer(invocation -> {
                Path p = invocation.getArgument(0);
                if (p.equals(saved)) {
                    throw new RuntimeException("exists boom");
                }
                return p.toFile().exists();
            });
            when(executionService.executeTestWithAutoCleanup(any()))
                    .thenReturn(ExecutionResponse.builder().status("success").executionTime(1L).build());
            assertThatThrownBy(() -> processor.process(msg))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("exists boom");
        }
    }

    @Test
    void finally_filesExistsThrowsAfterExecuteFailure_suppressesRunFailed() throws Exception {
        TestTaskMessage msg = baseValidMessage();
        String dir = (String) ReflectionTestUtils.getField(processor, "uploadDir");
        Path saved = Paths.get(dir).resolve(msg.getTestFileName());
        try (MockedStatic<Files> files = Mockito.mockStatic(Files.class, Mockito.withSettings().defaultAnswer(Mockito.CALLS_REAL_METHODS))) {
            files.when(() -> Files.exists(any(Path.class))).thenAnswer(invocation -> {
                Path p = invocation.getArgument(0);
                if (p.equals(saved)) {
                    throw new RuntimeException("exists boom");
                }
                return p.toFile().exists();
            });
            when(executionService.executeTestWithAutoCleanup(any())).thenThrow(new RuntimeException("run failed"));
            assertThatThrownBy(() -> processor.process(msg))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("exists boom");
        }
    }

    @Test
    void success_callsExecutionService() throws Exception {
        UUID taskId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        TestTaskMessage msg = new TestTaskMessage();
        msg.setTaskId(taskId.toString());
        msg.setTestFileName("hello.txt");
        msg.setTestFileContent(Base64.getEncoder().encodeToString("hi".getBytes(StandardCharsets.UTF_8)));
        msg.setExpectedDurationSeconds(10);
        msg.setCommand("echo run");
        msg.setTestTool("k6");
        msg.setDockerExecutionProfileId(profileId.toString());

        when(executionService.executeTestWithAutoCleanup(any()))
                .thenReturn(ExecutionResponse.builder().status("success").executionTime(2L).build());

        assertThat(processor.process(msg)).isNotNull();

        ArgumentCaptor<ExecutionRequest> cap = ArgumentCaptor.forClass(ExecutionRequest.class);
        verify(executionService).executeTestWithAutoCleanup(cap.capture());
        assertThat(cap.getValue().getTaskId()).isEqualTo(taskId);
        assertThat(cap.getValue().getDockerExecutionProfileId()).isEqualTo(profileId);
    }
}
