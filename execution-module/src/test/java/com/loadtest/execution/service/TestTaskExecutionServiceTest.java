package com.loadtest.execution.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadtest.execution.dto.ExecutionResponse;
import com.loadtest.execution.dto.TaskProcessOutcome;
import com.loadtest.execution.dto.TestTaskEvent;
import com.loadtest.execution.dto.TestTaskMessage;
import com.loadtest.execution.persistence.DockerExecutionProfileEntity;
import com.loadtest.execution.persistence.DockerExecutionProfileRepository;
import com.loadtest.execution.persistence.TestTaskEntity;
import com.loadtest.execution.persistence.TestTaskHistoryEntity;
import com.loadtest.execution.persistence.TestTaskHistoryRepository;
import com.loadtest.execution.persistence.TestTaskRepository;
import com.loadtest.execution.persistence.TestTaskStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TestTaskExecutionServiceTest {

    @Mock private TestTaskRepository taskRepository;
    @Mock private TestTaskHistoryRepository historyRepository;
    @Mock private TestTaskProcessor processor;
    @Mock private EntityManager entityManager;
    @Mock private DockerExecutionProfileRepository dockerExecutionProfileRepository;
    @Mock private Query nativeQuery;

    private TestTaskExecutionService service;

    @BeforeEach
    void setUp() {
        service = new TestTaskExecutionService(
                taskRepository,
                historyRepository,
                processor,
                new ObjectMapper(),
                entityManager,
                dockerExecutionProfileRepository
        );
    }

    @Test
    void execute_duplicateWhenNoTaskRowAfterClaim() throws Exception {
        UUID taskId = UUID.randomUUID();
        when(entityManager.createNativeQuery(anyString())).thenReturn(nativeQuery);
        when(nativeQuery.setParameter(anyString(), any())).thenReturn(nativeQuery);
        when(nativeQuery.executeUpdate()).thenReturn(0);
        when(taskRepository.findById(taskId)).thenReturn(Optional.empty());

        TestTaskRunResult r = service.execute(new TestTaskEvent(taskId.toString()));

        assertThat(r.getKind()).isEqualTo(TestTaskRunResult.Kind.DUPLICATE);
        verify(processor, never()).process(any());
    }

    @Test
    void execute_duplicateWhenTaskNotPending() throws Exception {
        UUID taskId = UUID.randomUUID();
        when(entityManager.createNativeQuery(anyString())).thenReturn(nativeQuery);
        when(nativeQuery.setParameter(anyString(), any())).thenReturn(nativeQuery);
        when(nativeQuery.executeUpdate()).thenReturn(0);
        TestTaskEntity row = TestTaskEntity.builder()
                .id(taskId)
                .status(TestTaskStatus.PROCESSING)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .lockedAt(OffsetDateTime.now())
                .expectedDurationSeconds(3600)
                .testTool("k6")
                .testFileName("t.js")
                .testFileContentBase64("YQ==")
                .command("run")
                .dockerExecutionProfileId(UUID.randomUUID())
                .build();
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(row));

        TestTaskRunResult r = service.execute(new TestTaskEvent(taskId.toString()));

        assertThat(r.getKind()).isEqualTo(TestTaskRunResult.Kind.DUPLICATE);
        verify(processor, never()).process(any());
    }

    @Test
    void execute_reclaimsStaleProcessing_andRuns() throws Exception {
        UUID taskId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        ReflectionTestUtils.setField(service, "staleProcessingGraceSeconds", 600L);
        when(entityManager.createNativeQuery(anyString())).thenReturn(nativeQuery);
        when(nativeQuery.setParameter(anyString(), any())).thenReturn(nativeQuery);
        when(nativeQuery.executeUpdate()).thenReturn(0, 1);

        TestTaskEntity task = TestTaskEntity.builder()
                .id(taskId)
                .status(TestTaskStatus.PROCESSING)
                .createdAt(OffsetDateTime.now().minusHours(2))
                .updatedAt(OffsetDateTime.now().minusHours(2))
                .lockedAt(OffsetDateTime.now().minusHours(2))
                .expectedDurationSeconds(60)
                .testTool("k6")
                .testFileName("t.js")
                .testFileContentBase64("YQ==")
                .command("run")
                .metricsConfig(null)
                .dockerExecutionProfileId(profileId)
                .build();
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        when(historyRepository.findById(taskId))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(TestTaskHistoryEntity.builder()
                        .id(taskId)
                        .finalStatus("PROCESSING")
                        .createdAt(task.getCreatedAt())
                        .movedAt(OffsetDateTime.now())
                        .testTool("k6")
                        .testFileName("t.js")
                        .testFileContentBase64("YQ==")
                        .command("run")
                        .expectedDurationSeconds(60)
                        .dockerExecutionProfileId(profileId)
                        .build()));

        when(dockerExecutionProfileRepository.findById(profileId))
                .thenReturn(Optional.of(DockerExecutionProfileEntity.builder()
                        .id(profileId)
                        .name("default")
                        .enabled(true)
                        .maxConcurrentContainers(4)
                        .build()));

        ExecutionResponse resp = new ExecutionResponse("success", null, null, null, null, 3L, null, null);
        when(processor.process(any(TestTaskMessage.class)))
                .thenReturn(new TaskProcessOutcome(resp, 100L, 200L));

        TestTaskRunResult r = service.execute(new TestTaskEvent(taskId.toString()));

        assertThat(r.getKind()).isEqualTo(TestTaskRunResult.Kind.COMPLETED);
        verify(processor).process(any(TestTaskMessage.class));
    }

    @Test
    void execute_completed_movesHistory() throws Exception {
        UUID taskId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        when(entityManager.createNativeQuery(anyString())).thenReturn(nativeQuery);
        when(nativeQuery.setParameter(anyString(), any())).thenReturn(nativeQuery);
        when(nativeQuery.executeUpdate()).thenReturn(1);

        TestTaskEntity task = TestTaskEntity.builder()
                .id(taskId)
                .status(TestTaskStatus.PROCESSING)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .testTool("k6")
                .testFileName("t.js")
                .testFileContentBase64("YQ==")
                .command("run")
                .expectedDurationSeconds(60)
                .metricsConfig(null)
                .dockerExecutionProfileId(profileId)
                .build();
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        when(historyRepository.findById(taskId))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(TestTaskHistoryEntity.builder()
                        .id(taskId)
                        .finalStatus("PROCESSING")
                        .createdAt(task.getCreatedAt())
                        .movedAt(OffsetDateTime.now())
                        .testTool("k6")
                        .testFileName("t.js")
                        .testFileContentBase64("YQ==")
                        .command("run")
                        .expectedDurationSeconds(60)
                        .dockerExecutionProfileId(profileId)
                        .build()));

        when(dockerExecutionProfileRepository.findById(profileId))
                .thenReturn(Optional.of(DockerExecutionProfileEntity.builder()
                        .id(profileId)
                        .name("default")
                        .enabled(true)
                        .maxConcurrentContainers(4)
                        .build()));

        ExecutionResponse resp = new ExecutionResponse("success", null, null, null, null, 3L, null, null);
        when(processor.process(any(TestTaskMessage.class)))
                .thenReturn(new TaskProcessOutcome(resp, 100L, 200L));

        TestTaskRunResult r = service.execute(new TestTaskEvent(taskId.toString()));

        assertThat(r.getKind()).isEqualTo(TestTaskRunResult.Kind.COMPLETED);
        verify(taskRepository, never()).save(any(TestTaskEntity.class));
        ArgumentCaptor<TestTaskHistoryEntity> historyCaptor = ArgumentCaptor.forClass(TestTaskHistoryEntity.class);
        verify(historyRepository, org.mockito.Mockito.atLeastOnce()).save(historyCaptor.capture());
        assertThat(historyCaptor.getAllValues().stream().map(TestTaskHistoryEntity::getFinalStatus))
                .contains("COMPLETED");
        verify(taskRepository).delete(any(TestTaskEntity.class));
    }

    @Test
    void execute_failedOnProcessor_stillUpdatesHistory() throws Exception {
        UUID taskId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        when(entityManager.createNativeQuery(anyString())).thenReturn(nativeQuery);
        when(nativeQuery.setParameter(anyString(), any())).thenReturn(nativeQuery);
        when(nativeQuery.executeUpdate()).thenReturn(1);

        TestTaskEntity task = TestTaskEntity.builder()
                .id(taskId)
                .status(TestTaskStatus.PROCESSING)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .testTool("k6")
                .testFileName("t.js")
                .testFileContentBase64("YQ==")
                .command("run")
                .dockerExecutionProfileId(profileId)
                .build();
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        when(historyRepository.findById(taskId))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(TestTaskHistoryEntity.builder()
                        .id(taskId)
                        .finalStatus("PROCESSING")
                        .createdAt(task.getCreatedAt())
                        .movedAt(OffsetDateTime.now())
                        .testTool("k6")
                        .testFileName("t.js")
                        .testFileContentBase64("YQ==")
                        .command("run")
                        .dockerExecutionProfileId(profileId)
                        .build()));

        when(dockerExecutionProfileRepository.findById(profileId))
                .thenReturn(Optional.of(DockerExecutionProfileEntity.builder()
                        .id(profileId)
                        .name("default")
                        .enabled(true)
                        .maxConcurrentContainers(4)
                        .build()));

        when(processor.process(any(TestTaskMessage.class))).thenThrow(new IllegalStateException("proc-fail"));

        TestTaskRunResult r = service.execute(new TestTaskEvent(taskId.toString()));

        assertThat(r.getKind()).isEqualTo(TestTaskRunResult.Kind.FAILED);
        verify(taskRepository).save(any(TestTaskEntity.class));
    }

    @Test
    void hasNonEmptyMetricsRequestsJson_allBranches() throws Exception {
        Method m = TestTaskExecutionService.class.getDeclaredMethod("hasNonEmptyMetricsRequestsJson", String.class);
        m.setAccessible(true);
        assertThat(m.invoke(service, new Object[] {null})).isEqualTo(false);
        assertThat(m.invoke(service, "")).isEqualTo(false);
        assertThat(m.invoke(service, "   ")).isEqualTo(false);
        assertThat(m.invoke(service, "{\"requests\":[]}")).isEqualTo(false);
        assertThat(m.invoke(service, "{\"requests\":\"x\"}")).isEqualTo(false);
        assertThat(m.invoke(service, "{\"foo\":1}")).isEqualTo(false);
        assertThat(m.invoke(service, "not-json")).isEqualTo(false);
        assertThat(m.invoke(service, "{\"requests\":[{\"n\":1}]}")).isEqualTo(true);
    }

    @Test
    void execute_completed_setsHasNonEmptyMetricsWhenJsonHasRequests() throws Exception {
        UUID taskId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        when(entityManager.createNativeQuery(anyString())).thenReturn(nativeQuery);
        when(nativeQuery.setParameter(anyString(), any())).thenReturn(nativeQuery);
        when(nativeQuery.executeUpdate()).thenReturn(1);

        TestTaskEntity task = TestTaskEntity.builder()
                .id(taskId)
                .status(TestTaskStatus.PROCESSING)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .testTool("k6")
                .testFileName("t.js")
                .testFileContentBase64("YQ==")
                .command("run")
                .expectedDurationSeconds(60)
                .metricsConfig("{\"requests\":[{\"name\":\"p\"}]}")
                .dockerExecutionProfileId(profileId)
                .build();
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        when(historyRepository.findById(taskId))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(TestTaskHistoryEntity.builder()
                        .id(taskId)
                        .finalStatus("PROCESSING")
                        .createdAt(task.getCreatedAt())
                        .movedAt(OffsetDateTime.now())
                        .testTool("k6")
                        .testFileName("t.js")
                        .testFileContentBase64("YQ==")
                        .command("run")
                        .expectedDurationSeconds(60)
                        .dockerExecutionProfileId(profileId)
                        .build()));

        when(dockerExecutionProfileRepository.findById(profileId))
                .thenReturn(Optional.of(DockerExecutionProfileEntity.builder()
                        .id(profileId)
                        .name("default")
                        .enabled(true)
                        .maxConcurrentContainers(4)
                        .build()));

        ExecutionResponse resp = new ExecutionResponse("success", null, null, null, null, 3L, null, null);
        when(processor.process(any(TestTaskMessage.class)))
                .thenReturn(new TaskProcessOutcome(resp, 100L, 200L));

        TestTaskRunResult r = service.execute(new TestTaskEvent(taskId.toString()));

        assertThat(r.getKind()).isEqualTo(TestTaskRunResult.Kind.COMPLETED);
        assertThat(r.hasNonEmptyMetricsRequests()).isTrue();
    }

    @Test
    void execute_interruptedWhileWaitingDockerConcurrency_returnsFailed() throws Exception {
        UUID taskId = UUID.randomUUID();
        TestTaskEntity pending = TestTaskEntity.builder()
                .id(taskId)
                .status(TestTaskStatus.PENDING)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .testTool("k6")
                .testFileName("t.js")
                .testFileContentBase64("YQ==")
                .command("run")
                .dockerExecutionProfileId(UUID.randomUUID())
                .build();
        when(entityManager.createNativeQuery(anyString())).thenReturn(nativeQuery);
        when(nativeQuery.setParameter(anyString(), any())).thenReturn(nativeQuery);
        when(nativeQuery.executeUpdate()).thenReturn(0);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(pending));

        TestTaskExecutionService interruptingSvc = new TestTaskExecutionService(
                taskRepository,
                historyRepository,
                processor,
                new ObjectMapper(),
                entityManager,
                dockerExecutionProfileRepository) {
            @Override
            protected void sleepForDockerRetry() throws InterruptedException {
                throw new InterruptedException();
            }
        };

        TestTaskRunResult r = interruptingSvc.execute(new TestTaskEvent(taskId.toString()));
        assertThat(r.getKind()).isEqualTo(TestTaskRunResult.Kind.FAILED);
        verify(processor, never()).process(any());
    }

    @Test
    void execute_finallyCatchesWhenTaskStatusSaveFails() throws Exception {
        UUID taskId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        when(entityManager.createNativeQuery(anyString())).thenReturn(nativeQuery);
        when(nativeQuery.setParameter(anyString(), any())).thenReturn(nativeQuery);
        when(nativeQuery.executeUpdate()).thenReturn(1);

        TestTaskEntity task = TestTaskEntity.builder()
                .id(taskId)
                .status(TestTaskStatus.PROCESSING)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .testTool("k6")
                .testFileName("t.js")
                .testFileContentBase64("YQ==")
                .command("run")
                .expectedDurationSeconds(60)
                .metricsConfig(null)
                .dockerExecutionProfileId(profileId)
                .build();
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        when(historyRepository.findById(taskId))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(TestTaskHistoryEntity.builder()
                        .id(taskId)
                        .finalStatus("PROCESSING")
                        .createdAt(task.getCreatedAt())
                        .movedAt(OffsetDateTime.now())
                        .testTool("k6")
                        .testFileName("t.js")
                        .testFileContentBase64("YQ==")
                        .command("run")
                        .expectedDurationSeconds(60)
                        .dockerExecutionProfileId(profileId)
                        .build()));

        when(dockerExecutionProfileRepository.findById(profileId))
                .thenReturn(Optional.of(DockerExecutionProfileEntity.builder()
                        .id(profileId)
                        .name("default")
                        .enabled(true)
                        .maxConcurrentContainers(4)
                        .build()));

        ExecutionResponse resp = new ExecutionResponse("success", null, null, null, null, 3L, null, null);
        when(processor.process(any(TestTaskMessage.class)))
                .thenReturn(new TaskProcessOutcome(resp, 100L, 200L));

        org.mockito.Mockito.doAnswer(invocation -> {
            TestTaskHistoryEntity h = invocation.getArgument(0);
            if (h.getFinalStatus() != null && !"PROCESSING".equals(h.getFinalStatus())) {
                throw new RuntimeException("history-save-fail");
            }
            return h;
        }).when(historyRepository).save(any(TestTaskHistoryEntity.class));

        TestTaskRunResult r = service.execute(new TestTaskEvent(taskId.toString()));

        assertThat(r.getKind()).isEqualTo(TestTaskRunResult.Kind.COMPLETED);
        verify(taskRepository, never()).save(any(TestTaskEntity.class));
        verify(historyRepository, org.mockito.Mockito.atLeastOnce()).save(any(TestTaskHistoryEntity.class));
    }

    @Test
    void execute_invalidMetricsConfigJson_logsParseWarning() throws Exception {
        UUID taskId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        when(entityManager.createNativeQuery(anyString())).thenReturn(nativeQuery);
        when(nativeQuery.setParameter(anyString(), any())).thenReturn(nativeQuery);
        when(nativeQuery.executeUpdate()).thenReturn(1);

        TestTaskEntity task = TestTaskEntity.builder()
                .id(taskId)
                .status(TestTaskStatus.PROCESSING)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .testTool("k6")
                .testFileName("t.js")
                .testFileContentBase64("YQ==")
                .command("run")
                .expectedDurationSeconds(60)
                .metricsConfig("{not-json")
                .dockerExecutionProfileId(profileId)
                .build();
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        when(historyRepository.findById(taskId))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(TestTaskHistoryEntity.builder()
                        .id(taskId)
                        .finalStatus("PROCESSING")
                        .createdAt(task.getCreatedAt())
                        .movedAt(OffsetDateTime.now())
                        .testTool("k6")
                        .testFileName("t.js")
                        .testFileContentBase64("YQ==")
                        .command("run")
                        .expectedDurationSeconds(60)
                        .dockerExecutionProfileId(profileId)
                        .build()));

        when(dockerExecutionProfileRepository.findById(profileId))
                .thenReturn(Optional.of(DockerExecutionProfileEntity.builder()
                        .id(profileId)
                        .name("default")
                        .enabled(true)
                        .maxConcurrentContainers(4)
                        .build()));

        ExecutionResponse resp = new ExecutionResponse("success", null, null, null, null, 3L, null, null);
        when(processor.process(any(TestTaskMessage.class)))
                .thenReturn(new TaskProcessOutcome(resp, 1L, 2L));

        TestTaskRunResult r = service.execute(new TestTaskEvent(taskId.toString()));

        assertThat(r.getKind()).isEqualTo(TestTaskRunResult.Kind.COMPLETED);
        org.mockito.ArgumentCaptor<TestTaskMessage> cap = org.mockito.ArgumentCaptor.forClass(TestTaskMessage.class);
        verify(processor).process(cap.capture());
        assertThat(cap.getValue().metricsConfig()).isNull();
    }

    @Test
    void execute_existingHistory_updatesSummarizerFromTask() throws Exception {
        UUID taskId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        when(entityManager.createNativeQuery(anyString())).thenReturn(nativeQuery);
        when(nativeQuery.setParameter(anyString(), any())).thenReturn(nativeQuery);
        when(nativeQuery.executeUpdate()).thenReturn(1);

        TestTaskEntity task = TestTaskEntity.builder()
                .id(taskId)
                .status(TestTaskStatus.PROCESSING)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .testTool("k6")
                .testFileName("t.js")
                .testFileContentBase64("YQ==")
                .command("run")
                .expectedDurationSeconds(60)
                .metricsConfig(null)
                .summarizerName("from-task")
                .dockerExecutionProfileId(profileId)
                .build();
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        TestTaskHistoryEntity history = TestTaskHistoryEntity.builder()
                .id(taskId)
                .finalStatus("PROCESSING")
                .createdAt(task.getCreatedAt())
                .movedAt(OffsetDateTime.now())
                .testTool("k6")
                .testFileName("t.js")
                .testFileContentBase64("YQ==")
                .command("run")
                .expectedDurationSeconds(60)
                .dockerExecutionProfileId(profileId)
                .summarizerName(null)
                .build();
        when(historyRepository.findById(taskId)).thenReturn(Optional.of(history));

        ExecutionResponse resp = new ExecutionResponse("success", null, null, null, null, 3L, null, null);
        when(processor.process(any(TestTaskMessage.class)))
                .thenReturn(new TaskProcessOutcome(resp, 1L, 2L));

        service.execute(new TestTaskEvent(taskId.toString()));

        assertThat(history.getSummarizerName()).isEqualTo("from-task");
        verify(historyRepository, org.mockito.Mockito.atLeastOnce()).save(history);
    }

    @Test
    void execute_ensureHistorySaveFails_wrapsRuntime() throws Exception {
        UUID taskId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        when(entityManager.createNativeQuery(anyString())).thenReturn(nativeQuery);
        when(nativeQuery.setParameter(anyString(), any())).thenReturn(nativeQuery);
        when(nativeQuery.executeUpdate()).thenReturn(1);

        TestTaskEntity task = TestTaskEntity.builder()
                .id(taskId)
                .status(TestTaskStatus.PROCESSING)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .testTool("k6")
                .testFileName("t.js")
                .testFileContentBase64("YQ==")
                .command("run")
                .dockerExecutionProfileId(profileId)
                .build();
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        when(historyRepository.findById(taskId)).thenReturn(Optional.empty());
        when(dockerExecutionProfileRepository.findById(profileId))
                .thenReturn(Optional.of(DockerExecutionProfileEntity.builder()
                        .id(profileId)
                        .name("default")
                        .enabled(true)
                        .maxConcurrentContainers(4)
                        .build()));
        doThrow(new RuntimeException("db")).when(historyRepository).save(any(TestTaskHistoryEntity.class));

        assertThatThrownBy(() -> service.execute(new TestTaskEvent(taskId.toString())))
                .isInstanceOf(TestTaskHistoryException.class)
                .hasMessageContaining("Cannot create history record");
    }

    @Test
    void execute_updateHistoryCopiesSummarizerFromTaskRow() throws Exception {
        UUID taskId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        when(entityManager.createNativeQuery(anyString())).thenReturn(nativeQuery);
        when(nativeQuery.setParameter(anyString(), any())).thenReturn(nativeQuery);
        when(nativeQuery.executeUpdate()).thenReturn(1);

        OffsetDateTime created = OffsetDateTime.now();
        TestTaskEntity taskNoSummarizer = TestTaskEntity.builder()
                .id(taskId)
                .status(TestTaskStatus.PROCESSING)
                .createdAt(created)
                .updatedAt(OffsetDateTime.now())
                .testTool("k6")
                .testFileName("t.js")
                .testFileContentBase64("YQ==")
                .command("run")
                .expectedDurationSeconds(60)
                .metricsConfig(null)
                .summarizerName(null)
                .dockerExecutionProfileId(profileId)
                .build();
        TestTaskEntity taskWithSummarizer = TestTaskEntity.builder()
                .id(taskId)
                .status(TestTaskStatus.PROCESSING)
                .createdAt(created)
                .updatedAt(OffsetDateTime.now())
                .testTool("k6")
                .testFileName("t.js")
                .testFileContentBase64("YQ==")
                .command("run")
                .expectedDurationSeconds(60)
                .metricsConfig(null)
                .summarizerName("row-sum")
                .dockerExecutionProfileId(profileId)
                .build();
        when(taskRepository.findById(taskId))
                .thenReturn(Optional.of(taskNoSummarizer), Optional.of(taskWithSummarizer), Optional.of(taskWithSummarizer));

        TestTaskHistoryEntity history = TestTaskHistoryEntity.builder()
                .id(taskId)
                .finalStatus("PROCESSING")
                .createdAt(created)
                .movedAt(OffsetDateTime.now())
                .testTool("k6")
                .testFileName("t.js")
                .testFileContentBase64("YQ==")
                .command("run")
                .expectedDurationSeconds(60)
                .dockerExecutionProfileId(profileId)
                .summarizerName(null)
                .build();
        when(historyRepository.findById(taskId)).thenReturn(Optional.of(history));

        ExecutionResponse resp = new ExecutionResponse("success", null, null, null, null, 3L, null, null);
        when(processor.process(any(TestTaskMessage.class)))
                .thenReturn(new TaskProcessOutcome(resp, 1L, 2L));

        service.execute(new TestTaskEvent(taskId.toString()));

        assertThat(history.getSummarizerName()).isEqualTo("row-sum");
    }

    @Test
    void execute_updateHistorySaveFails_logsAndCompletes() throws Exception {
        UUID taskId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        when(entityManager.createNativeQuery(anyString())).thenReturn(nativeQuery);
        when(nativeQuery.setParameter(anyString(), any())).thenReturn(nativeQuery);
        when(nativeQuery.executeUpdate()).thenReturn(1);

        TestTaskEntity task = TestTaskEntity.builder()
                .id(taskId)
                .status(TestTaskStatus.PROCESSING)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .testTool("k6")
                .testFileName("t.js")
                .testFileContentBase64("YQ==")
                .command("run")
                .expectedDurationSeconds(60)
                .metricsConfig(null)
                .dockerExecutionProfileId(profileId)
                .build();
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        TestTaskHistoryEntity history = TestTaskHistoryEntity.builder()
                .id(taskId)
                .finalStatus("PROCESSING")
                .createdAt(task.getCreatedAt())
                .movedAt(OffsetDateTime.now())
                .testTool("k6")
                .testFileName("t.js")
                .testFileContentBase64("YQ==")
                .command("run")
                .expectedDurationSeconds(60)
                .dockerExecutionProfileId(profileId)
                .build();
        when(historyRepository.findById(taskId)).thenReturn(Optional.of(history));

        ExecutionResponse resp = new ExecutionResponse("success", null, null, null, null, 3L, null, null);
        when(processor.process(any(TestTaskMessage.class)))
                .thenReturn(new TaskProcessOutcome(resp, 1L, 2L));

        doThrow(new RuntimeException("hist-update")).when(historyRepository).save(any(TestTaskHistoryEntity.class));

        TestTaskRunResult r = service.execute(new TestTaskEvent(taskId.toString()));

        assertThat(r.getKind()).isEqualTo(TestTaskRunResult.Kind.COMPLETED);
    }

    @Test
    void execute_dockerConcurrencyFull_retriesSleepThenClaims() throws Exception {
        UUID taskId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        when(entityManager.createNativeQuery(anyString())).thenReturn(nativeQuery);
        when(nativeQuery.setParameter(anyString(), any())).thenReturn(nativeQuery);
        when(nativeQuery.executeUpdate()).thenReturn(0, 1);

        TestTaskEntity pending = TestTaskEntity.builder()
                .id(taskId)
                .status(TestTaskStatus.PENDING)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .testTool("k6")
                .testFileName("t.js")
                .testFileContentBase64("YQ==")
                .command("run")
                .expectedDurationSeconds(60)
                .metricsConfig(null)
                .dockerExecutionProfileId(profileId)
                .build();
        TestTaskEntity processing = TestTaskEntity.builder()
                .id(taskId)
                .status(TestTaskStatus.PROCESSING)
                .createdAt(pending.getCreatedAt())
                .updatedAt(OffsetDateTime.now())
                .testTool("k6")
                .testFileName("t.js")
                .testFileContentBase64("YQ==")
                .command("run")
                .expectedDurationSeconds(60)
                .metricsConfig(null)
                .dockerExecutionProfileId(profileId)
                .build();
        when(taskRepository.findById(taskId))
                .thenReturn(
                        Optional.of(pending),
                        Optional.of(processing),
                        Optional.of(processing),
                        Optional.of(processing));

        when(historyRepository.findById(taskId))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(TestTaskHistoryEntity.builder()
                        .id(taskId)
                        .finalStatus("PROCESSING")
                        .createdAt(pending.getCreatedAt())
                        .movedAt(OffsetDateTime.now())
                        .testTool("k6")
                        .testFileName("t.js")
                        .testFileContentBase64("YQ==")
                        .command("run")
                        .expectedDurationSeconds(60)
                        .dockerExecutionProfileId(profileId)
                        .build()));

        when(dockerExecutionProfileRepository.findById(profileId))
                .thenReturn(Optional.of(DockerExecutionProfileEntity.builder()
                        .id(profileId)
                        .name("default")
                        .enabled(true)
                        .maxConcurrentContainers(4)
                        .build()));

        ExecutionResponse resp = new ExecutionResponse("success", null, null, null, null, 3L, null, null);
        when(processor.process(any(TestTaskMessage.class)))
                .thenReturn(new TaskProcessOutcome(resp, 1L, 2L));

        TestTaskExecutionService fastRetrySvc = new TestTaskExecutionService(
                taskRepository,
                historyRepository,
                processor,
                new ObjectMapper(),
                entityManager,
                dockerExecutionProfileRepository) {
            @Override
            protected void sleepForDockerRetry() {
            }
        };

        TestTaskRunResult r = fastRetrySvc.execute(new TestTaskEvent(taskId.toString()));
        assertThat(r.getKind()).isEqualTo(TestTaskRunResult.Kind.COMPLETED);
        verify(nativeQuery, times(2)).executeUpdate();
    }

    @Test
    void execute_finally_skipsTaskSaveWhenTaskRowDisappeared() throws Exception {
        UUID taskId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        when(entityManager.createNativeQuery(anyString())).thenReturn(nativeQuery);
        when(nativeQuery.setParameter(anyString(), any())).thenReturn(nativeQuery);
        when(nativeQuery.executeUpdate()).thenReturn(1);

        TestTaskEntity task = TestTaskEntity.builder()
                .id(taskId)
                .status(TestTaskStatus.PROCESSING)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .testTool("k6")
                .testFileName("t.js")
                .testFileContentBase64("YQ==")
                .command("run")
                .expectedDurationSeconds(60)
                .metricsConfig(null)
                .dockerExecutionProfileId(profileId)
                .build();
        when(taskRepository.findById(taskId))
                .thenReturn(Optional.of(task), Optional.empty(), Optional.empty());

        when(historyRepository.findById(taskId))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(TestTaskHistoryEntity.builder()
                        .id(taskId)
                        .finalStatus("PROCESSING")
                        .createdAt(task.getCreatedAt())
                        .movedAt(OffsetDateTime.now())
                        .testTool("k6")
                        .testFileName("t.js")
                        .testFileContentBase64("YQ==")
                        .command("run")
                        .expectedDurationSeconds(60)
                        .dockerExecutionProfileId(profileId)
                        .build()));

        when(dockerExecutionProfileRepository.findById(profileId))
                .thenReturn(Optional.of(DockerExecutionProfileEntity.builder()
                        .id(profileId)
                        .name("default")
                        .enabled(true)
                        .maxConcurrentContainers(4)
                        .build()));

        ExecutionResponse resp = new ExecutionResponse("success", null, null, null, null, 3L, null, null);
        when(processor.process(any(TestTaskMessage.class)))
                .thenReturn(new TaskProcessOutcome(resp, 1L, 2L));

        TestTaskRunResult r = service.execute(new TestTaskEvent(taskId.toString()));

        assertThat(r.getKind()).isEqualTo(TestTaskRunResult.Kind.COMPLETED);
        verify(taskRepository, never()).save(any(TestTaskEntity.class));
        verify(taskRepository, never()).delete(any(TestTaskEntity.class));
    }

    @Test
    void execute_finally_onProcessorFailure_skipsTaskSaveWhenTaskRowDisappeared() throws Exception {
        UUID taskId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        when(entityManager.createNativeQuery(anyString())).thenReturn(nativeQuery);
        when(nativeQuery.setParameter(anyString(), any())).thenReturn(nativeQuery);
        when(nativeQuery.executeUpdate()).thenReturn(1);

        TestTaskEntity task = TestTaskEntity.builder()
                .id(taskId)
                .status(TestTaskStatus.PROCESSING)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .testTool("k6")
                .testFileName("t.js")
                .testFileContentBase64("YQ==")
                .command("run")
                .expectedDurationSeconds(60)
                .metricsConfig(null)
                .dockerExecutionProfileId(profileId)
                .build();
        when(taskRepository.findById(taskId))
                .thenReturn(Optional.of(task), Optional.empty(), Optional.empty());

        when(historyRepository.findById(taskId))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(TestTaskHistoryEntity.builder()
                        .id(taskId)
                        .finalStatus("PROCESSING")
                        .createdAt(task.getCreatedAt())
                        .movedAt(OffsetDateTime.now())
                        .testTool("k6")
                        .testFileName("t.js")
                        .testFileContentBase64("YQ==")
                        .command("run")
                        .expectedDurationSeconds(60)
                        .dockerExecutionProfileId(profileId)
                        .build()));

        when(dockerExecutionProfileRepository.findById(profileId))
                .thenReturn(Optional.of(DockerExecutionProfileEntity.builder()
                        .id(profileId)
                        .name("default")
                        .enabled(true)
                        .maxConcurrentContainers(4)
                        .build()));

        when(processor.process(any(TestTaskMessage.class))).thenThrow(new IllegalStateException("proc-fail"));

        TestTaskRunResult r = service.execute(new TestTaskEvent(taskId.toString()));

        assertThat(r.getKind()).isEqualTo(TestTaskRunResult.Kind.FAILED);
        verify(taskRepository, never()).save(any(TestTaskEntity.class));
        verify(taskRepository, never()).delete(any(TestTaskEntity.class));
    }

    @Test
    void execute_finally_findByIdThrowsAfterSuccess_innerCatchDoesNotReThrow() throws Exception {
        UUID taskId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        when(entityManager.createNativeQuery(anyString())).thenReturn(nativeQuery);
        when(nativeQuery.setParameter(anyString(), any())).thenReturn(nativeQuery);
        when(nativeQuery.executeUpdate()).thenReturn(1);

        TestTaskEntity task = TestTaskEntity.builder()
                .id(taskId)
                .status(TestTaskStatus.PROCESSING)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .testTool("k6")
                .testFileName("t.js")
                .testFileContentBase64("YQ==")
                .command("run")
                .expectedDurationSeconds(60)
                .metricsConfig(null)
                .dockerExecutionProfileId(profileId)
                .build();
        when(taskRepository.findById(taskId))
                .thenReturn(Optional.of(task))
                .thenThrow(new RuntimeException("find in finally"));

        when(historyRepository.findById(taskId))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(TestTaskHistoryEntity.builder()
                        .id(taskId)
                        .finalStatus("PROCESSING")
                        .createdAt(task.getCreatedAt())
                        .movedAt(OffsetDateTime.now())
                        .testTool("k6")
                        .testFileName("t.js")
                        .testFileContentBase64("YQ==")
                        .command("run")
                        .expectedDurationSeconds(60)
                        .dockerExecutionProfileId(profileId)
                        .build()));

        when(dockerExecutionProfileRepository.findById(profileId))
                .thenReturn(Optional.of(DockerExecutionProfileEntity.builder()
                        .id(profileId)
                        .name("default")
                        .enabled(true)
                        .maxConcurrentContainers(4)
                        .build()));

        ExecutionResponse resp = new ExecutionResponse("success", null, null, null, null, 3L, null, null);
        when(processor.process(any(TestTaskMessage.class)))
                .thenReturn(new TaskProcessOutcome(resp, 1L, 2L));

        TestTaskRunResult r = service.execute(new TestTaskEvent(taskId.toString()));

        assertThat(r.getKind()).isEqualTo(TestTaskRunResult.Kind.COMPLETED);
        verify(taskRepository, never()).save(any(TestTaskEntity.class));
    }

    @Test
    void execute_finally_findByIdThrowsAfterProcessorFailure_innerCatch() throws Exception {
        UUID taskId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        when(entityManager.createNativeQuery(anyString())).thenReturn(nativeQuery);
        when(nativeQuery.setParameter(anyString(), any())).thenReturn(nativeQuery);
        when(nativeQuery.executeUpdate()).thenReturn(1);

        TestTaskEntity task = TestTaskEntity.builder()
                .id(taskId)
                .status(TestTaskStatus.PROCESSING)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .testTool("k6")
                .testFileName("t.js")
                .testFileContentBase64("YQ==")
                .command("run")
                .expectedDurationSeconds(60)
                .metricsConfig(null)
                .dockerExecutionProfileId(profileId)
                .build();
        when(taskRepository.findById(taskId))
                .thenReturn(Optional.of(task))
                .thenThrow(new RuntimeException("find in finally"));

        when(historyRepository.findById(taskId))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(TestTaskHistoryEntity.builder()
                        .id(taskId)
                        .finalStatus("PROCESSING")
                        .createdAt(task.getCreatedAt())
                        .movedAt(OffsetDateTime.now())
                        .testTool("k6")
                        .testFileName("t.js")
                        .testFileContentBase64("YQ==")
                        .command("run")
                        .expectedDurationSeconds(60)
                        .dockerExecutionProfileId(profileId)
                        .build()));

        when(dockerExecutionProfileRepository.findById(profileId))
                .thenReturn(Optional.of(DockerExecutionProfileEntity.builder()
                        .id(profileId)
                        .name("default")
                        .enabled(true)
                        .maxConcurrentContainers(4)
                        .build()));

        when(processor.process(any(TestTaskMessage.class))).thenThrow(new IllegalStateException("proc-fail"));

        TestTaskRunResult r = service.execute(new TestTaskEvent(taskId.toString()));

        assertThat(r.getKind()).isEqualTo(TestTaskRunResult.Kind.FAILED);
        verify(taskRepository, never()).save(any(TestTaskEntity.class));
    }

    @Test
    void execute_completed_nullProcessOutcome_returnsFailed() throws Exception {
        UUID taskId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        when(entityManager.createNativeQuery(anyString())).thenReturn(nativeQuery);
        when(nativeQuery.setParameter(anyString(), any())).thenReturn(nativeQuery);
        when(nativeQuery.executeUpdate()).thenReturn(1);

        TestTaskEntity task = TestTaskEntity.builder()
                .id(taskId)
                .status(TestTaskStatus.PROCESSING)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .testTool("k6")
                .testFileName("t.js")
                .testFileContentBase64("YQ==")
                .command("run")
                .expectedDurationSeconds(60)
                .metricsConfig(null)
                .dockerExecutionProfileId(profileId)
                .build();
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        when(historyRepository.findById(taskId))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(TestTaskHistoryEntity.builder()
                        .id(taskId)
                        .finalStatus("PROCESSING")
                        .createdAt(task.getCreatedAt())
                        .movedAt(OffsetDateTime.now())
                        .testTool("k6")
                        .testFileName("t.js")
                        .testFileContentBase64("YQ==")
                        .command("run")
                        .expectedDurationSeconds(60)
                        .dockerExecutionProfileId(profileId)
                        .build()));

        when(dockerExecutionProfileRepository.findById(profileId))
                .thenReturn(Optional.of(DockerExecutionProfileEntity.builder()
                        .id(profileId)
                        .name("default")
                        .enabled(true)
                        .maxConcurrentContainers(4)
                        .build()));

        when(processor.process(any(TestTaskMessage.class))).thenReturn(null);

        TestTaskRunResult r = service.execute(new TestTaskEvent(taskId.toString()));

        assertThat(r.getKind()).isEqualTo(TestTaskRunResult.Kind.FAILED);
    }

    @Test
    void execute_toMessage_skipsDockerProfileWhenNull() throws Exception {
        UUID taskId = UUID.randomUUID();
        when(entityManager.createNativeQuery(anyString())).thenReturn(nativeQuery);
        when(nativeQuery.setParameter(anyString(), any())).thenReturn(nativeQuery);
        when(nativeQuery.executeUpdate()).thenReturn(1);

        TestTaskEntity task = TestTaskEntity.builder()
                .id(taskId)
                .status(TestTaskStatus.PROCESSING)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .testTool("k6")
                .testFileName("t.js")
                .testFileContentBase64("YQ==")
                .command("run")
                .expectedDurationSeconds(60)
                .metricsConfig(null)
                .dockerExecutionProfileId(null)
                .build();
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        UUID histProfileId = UUID.randomUUID();
        when(historyRepository.findById(taskId)).thenReturn(Optional.of(TestTaskHistoryEntity.builder()
                .id(taskId)
                .finalStatus("PROCESSING")
                .createdAt(task.getCreatedAt())
                .movedAt(OffsetDateTime.now())
                .testTool("k6")
                .testFileName("t.js")
                .testFileContentBase64("YQ==")
                .command("run")
                .expectedDurationSeconds(60)
                .dockerExecutionProfileId(histProfileId)
                .build()));

        ExecutionResponse resp = new ExecutionResponse("success", null, null, null, null, 3L, null, null);
        when(processor.process(any(TestTaskMessage.class)))
                .thenReturn(new TaskProcessOutcome(resp, 1L, 2L));

        service.execute(new TestTaskEvent(taskId.toString()));

        ArgumentCaptor<TestTaskMessage> cap = ArgumentCaptor.forClass(TestTaskMessage.class);
        verify(processor).process(cap.capture());
        assertThat(cap.getValue().dockerExecutionProfileId()).isNull();
    }

    @Test
    void execute_metricsConfigWhitespaceOnly_skipsMetricsParse() throws Exception {
        UUID taskId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        when(entityManager.createNativeQuery(anyString())).thenReturn(nativeQuery);
        when(nativeQuery.setParameter(anyString(), any())).thenReturn(nativeQuery);
        when(nativeQuery.executeUpdate()).thenReturn(1);

        TestTaskEntity task = TestTaskEntity.builder()
                .id(taskId)
                .status(TestTaskStatus.PROCESSING)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .testTool("k6")
                .testFileName("t.js")
                .testFileContentBase64("YQ==")
                .command("run")
                .expectedDurationSeconds(60)
                .metricsConfig(" \t \n ")
                .dockerExecutionProfileId(profileId)
                .build();
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        when(historyRepository.findById(taskId))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(TestTaskHistoryEntity.builder()
                        .id(taskId)
                        .finalStatus("PROCESSING")
                        .createdAt(task.getCreatedAt())
                        .movedAt(OffsetDateTime.now())
                        .testTool("k6")
                        .testFileName("t.js")
                        .testFileContentBase64("YQ==")
                        .command("run")
                        .expectedDurationSeconds(60)
                        .dockerExecutionProfileId(profileId)
                        .build()));

        when(dockerExecutionProfileRepository.findById(profileId))
                .thenReturn(Optional.of(DockerExecutionProfileEntity.builder()
                        .id(profileId)
                        .name("default")
                        .enabled(true)
                        .maxConcurrentContainers(4)
                        .build()));

        ExecutionResponse resp = new ExecutionResponse("success", null, null, null, null, 3L, null, null);
        when(processor.process(any(TestTaskMessage.class)))
                .thenReturn(new TaskProcessOutcome(resp, 1L, 2L));

        service.execute(new TestTaskEvent(taskId.toString()));

        ArgumentCaptor<TestTaskMessage> cap = ArgumentCaptor.forClass(TestTaskMessage.class);
        verify(processor).process(cap.capture());
        assertThat(cap.getValue().metricsConfig()).isNull();
    }

    @Test
    void execute_historyExists_doesNotOverwriteSummarizerWhenHistoryAlreadySet() throws Exception {
        UUID taskId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        when(entityManager.createNativeQuery(anyString())).thenReturn(nativeQuery);
        when(nativeQuery.setParameter(anyString(), any())).thenReturn(nativeQuery);
        when(nativeQuery.executeUpdate()).thenReturn(1);

        TestTaskEntity task = TestTaskEntity.builder()
                .id(taskId)
                .status(TestTaskStatus.PROCESSING)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .testTool("k6")
                .testFileName("t.js")
                .testFileContentBase64("YQ==")
                .command("run")
                .expectedDurationSeconds(60)
                .metricsConfig(null)
                .summarizerName("from-task")
                .dockerExecutionProfileId(profileId)
                .build();
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        TestTaskHistoryEntity history = TestTaskHistoryEntity.builder()
                .id(taskId)
                .finalStatus("PROCESSING")
                .createdAt(task.getCreatedAt())
                .movedAt(OffsetDateTime.now())
                .testTool("k6")
                .testFileName("t.js")
                .testFileContentBase64("YQ==")
                .command("run")
                .expectedDurationSeconds(60)
                .dockerExecutionProfileId(profileId)
                .summarizerName("already-set")
                .build();
        when(historyRepository.findById(taskId)).thenReturn(Optional.of(history));

        ExecutionResponse resp = new ExecutionResponse("success", null, null, null, null, 3L, null, null);
        when(processor.process(any(TestTaskMessage.class)))
                .thenReturn(new TaskProcessOutcome(resp, 1L, 2L));

        service.execute(new TestTaskEvent(taskId.toString()));

        assertThat(history.getSummarizerName()).isEqualTo("already-set");
    }

    @Test
    void ensureHistoryRecord_existing_taskSummarizerNull_doesNotSave() throws Exception {
        Method m = TestTaskExecutionService.class.getDeclaredMethod(
                "ensureHistoryRecordForArtifacts", TestTaskEntity.class, OffsetDateTime.class);
        m.setAccessible(true);
        UUID taskId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        OffsetDateTime created = OffsetDateTime.now();
        TestTaskEntity task = TestTaskEntity.builder()
                .id(taskId)
                .status(TestTaskStatus.PROCESSING)
                .createdAt(created)
                .updatedAt(OffsetDateTime.now())
                .testTool("k6")
                .testFileName("t.js")
                .testFileContentBase64("YQ==")
                .command("run")
                .summarizerName(null)
                .dockerExecutionProfileId(profileId)
                .build();
        TestTaskHistoryEntity h = TestTaskHistoryEntity.builder()
                .id(taskId)
                .finalStatus("PROCESSING")
                .createdAt(created)
                .movedAt(OffsetDateTime.now())
                .testTool("k6")
                .testFileName("t.js")
                .testFileContentBase64("YQ==")
                .command("run")
                .summarizerName(null)
                .dockerExecutionProfileId(profileId)
                .build();
        when(historyRepository.findById(taskId)).thenReturn(Optional.of(h));
        m.invoke(service, task, OffsetDateTime.now());
        verify(historyRepository, never()).save(any());
    }

    @Test
    void ensureHistoryRecord_existing_taskSummarizerWhitespaceOnly_doesNotSave() throws Exception {
        Method m = TestTaskExecutionService.class.getDeclaredMethod(
                "ensureHistoryRecordForArtifacts", TestTaskEntity.class, OffsetDateTime.class);
        m.setAccessible(true);
        UUID taskId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        OffsetDateTime created = OffsetDateTime.now();
        TestTaskEntity task = TestTaskEntity.builder()
                .id(taskId)
                .status(TestTaskStatus.PROCESSING)
                .createdAt(created)
                .updatedAt(OffsetDateTime.now())
                .testTool("k6")
                .testFileName("t.js")
                .testFileContentBase64("YQ==")
                .command("run")
                .summarizerName(" \t ")
                .dockerExecutionProfileId(profileId)
                .build();
        TestTaskHistoryEntity h = TestTaskHistoryEntity.builder()
                .id(taskId)
                .finalStatus("PROCESSING")
                .createdAt(created)
                .movedAt(OffsetDateTime.now())
                .testTool("k6")
                .testFileName("t.js")
                .testFileContentBase64("YQ==")
                .command("run")
                .summarizerName(null)
                .dockerExecutionProfileId(profileId)
                .build();
        when(historyRepository.findById(taskId)).thenReturn(Optional.of(h));
        m.invoke(service, task, OffsetDateTime.now());
        verify(historyRepository, never()).save(any());
    }

    @Test
    void ensureHistoryRecord_existing_updatesWhenHistorySummarizerIsWhitespaceOnly() throws Exception {
        Method m = TestTaskExecutionService.class.getDeclaredMethod(
                "ensureHistoryRecordForArtifacts", TestTaskEntity.class, OffsetDateTime.class);
        m.setAccessible(true);
        UUID taskId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        OffsetDateTime created = OffsetDateTime.now();
        TestTaskEntity task = TestTaskEntity.builder()
                .id(taskId)
                .status(TestTaskStatus.PROCESSING)
                .createdAt(created)
                .updatedAt(OffsetDateTime.now())
                .testTool("k6")
                .testFileName("t.js")
                .testFileContentBase64("YQ==")
                .command("run")
                .summarizerName("from-task")
                .dockerExecutionProfileId(profileId)
                .build();
        TestTaskHistoryEntity h = TestTaskHistoryEntity.builder()
                .id(taskId)
                .finalStatus("PROCESSING")
                .createdAt(created)
                .movedAt(OffsetDateTime.now())
                .testTool("k6")
                .testFileName("t.js")
                .testFileContentBase64("YQ==")
                .command("run")
                .summarizerName("  \t  ")
                .dockerExecutionProfileId(profileId)
                .build();
        when(historyRepository.findById(taskId)).thenReturn(Optional.of(h));
        m.invoke(service, task, OffsetDateTime.now());
        assertThat(h.getSummarizerName()).isEqualTo("from-task");
        verify(historyRepository).save(h);
    }

    @Test
    void execute_updateHistory_copiesSummarizerWhenHistorySummarizerIsWhitespaceOnly() throws Exception {
        UUID taskId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        when(entityManager.createNativeQuery(anyString())).thenReturn(nativeQuery);
        when(nativeQuery.setParameter(anyString(), any())).thenReturn(nativeQuery);
        when(nativeQuery.executeUpdate()).thenReturn(1);

        OffsetDateTime created = OffsetDateTime.now();
        TestTaskEntity taskNoSummarizer = TestTaskEntity.builder()
                .id(taskId)
                .status(TestTaskStatus.PROCESSING)
                .createdAt(created)
                .updatedAt(OffsetDateTime.now())
                .testTool("k6")
                .testFileName("t.js")
                .testFileContentBase64("YQ==")
                .command("run")
                .expectedDurationSeconds(60)
                .metricsConfig(null)
                .summarizerName(null)
                .dockerExecutionProfileId(profileId)
                .build();
        TestTaskEntity taskWithSummarizer = TestTaskEntity.builder()
                .id(taskId)
                .status(TestTaskStatus.PROCESSING)
                .createdAt(created)
                .updatedAt(OffsetDateTime.now())
                .testTool("k6")
                .testFileName("t.js")
                .testFileContentBase64("YQ==")
                .command("run")
                .expectedDurationSeconds(60)
                .metricsConfig(null)
                .summarizerName("row-sum")
                .dockerExecutionProfileId(profileId)
                .build();
        when(taskRepository.findById(taskId))
                .thenReturn(Optional.of(taskNoSummarizer), Optional.of(taskWithSummarizer), Optional.of(taskWithSummarizer));

        TestTaskHistoryEntity history = TestTaskHistoryEntity.builder()
                .id(taskId)
                .finalStatus("PROCESSING")
                .createdAt(created)
                .movedAt(OffsetDateTime.now())
                .testTool("k6")
                .testFileName("t.js")
                .testFileContentBase64("YQ==")
                .command("run")
                .expectedDurationSeconds(60)
                .dockerExecutionProfileId(profileId)
                .summarizerName(" \t ")
                .build();
        when(historyRepository.findById(taskId)).thenReturn(Optional.of(history));

        ExecutionResponse resp = new ExecutionResponse("success", null, null, null, null, 3L, null, null);
        when(processor.process(any(TestTaskMessage.class)))
                .thenReturn(new TaskProcessOutcome(resp, 1L, 2L));

        service.execute(new TestTaskEvent(taskId.toString()));

        assertThat(history.getSummarizerName()).isEqualTo("row-sum");
    }

    @Test
    void execute_updateHistory_blankTaskRowSummarizer_doesNotCopyToHistory() throws Exception {
        UUID taskId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        when(entityManager.createNativeQuery(anyString())).thenReturn(nativeQuery);
        when(nativeQuery.setParameter(anyString(), any())).thenReturn(nativeQuery);
        when(nativeQuery.executeUpdate()).thenReturn(1);

        OffsetDateTime created = OffsetDateTime.now();
        TestTaskEntity taskLine70 = TestTaskEntity.builder()
                .id(taskId)
                .status(TestTaskStatus.PROCESSING)
                .createdAt(created)
                .updatedAt(OffsetDateTime.now())
                .testTool("k6")
                .testFileName("t.js")
                .testFileContentBase64("YQ==")
                .command("run")
                .expectedDurationSeconds(60)
                .metricsConfig(null)
                .summarizerName(null)
                .dockerExecutionProfileId(profileId)
                .build();
        TestTaskEntity taskBlankSummarizer = TestTaskEntity.builder()
                .id(taskId)
                .status(TestTaskStatus.PROCESSING)
                .createdAt(created)
                .updatedAt(OffsetDateTime.now())
                .testTool("k6")
                .testFileName("t.js")
                .testFileContentBase64("YQ==")
                .command("run")
                .expectedDurationSeconds(60)
                .metricsConfig(null)
                .summarizerName(" \t ")
                .dockerExecutionProfileId(profileId)
                .build();
        when(taskRepository.findById(taskId))
                .thenReturn(Optional.of(taskLine70), Optional.of(taskBlankSummarizer), Optional.of(taskBlankSummarizer));

        TestTaskHistoryEntity history = TestTaskHistoryEntity.builder()
                .id(taskId)
                .finalStatus("PROCESSING")
                .createdAt(created)
                .movedAt(OffsetDateTime.now())
                .testTool("k6")
                .testFileName("t.js")
                .testFileContentBase64("YQ==")
                .command("run")
                .expectedDurationSeconds(60)
                .dockerExecutionProfileId(profileId)
                .summarizerName(null)
                .build();
        when(historyRepository.findById(taskId)).thenReturn(Optional.of(history));

        ExecutionResponse resp = new ExecutionResponse("success", null, null, null, null, 3L, null, null);
        when(processor.process(any(TestTaskMessage.class)))
                .thenReturn(new TaskProcessOutcome(resp, 1L, 2L));

        service.execute(new TestTaskEvent(taskId.toString()));

        assertThat(history.getSummarizerName()).isNull();
    }

    @Test
    void execute_updateHistory_taskRowGone_skipsDelete() throws Exception {
        UUID taskId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        when(entityManager.createNativeQuery(anyString())).thenReturn(nativeQuery);
        when(nativeQuery.setParameter(anyString(), any())).thenReturn(nativeQuery);
        when(nativeQuery.executeUpdate()).thenReturn(1);

        TestTaskEntity task = TestTaskEntity.builder()
                .id(taskId)
                .status(TestTaskStatus.PROCESSING)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .testTool("k6")
                .testFileName("t.js")
                .testFileContentBase64("YQ==")
                .command("run")
                .expectedDurationSeconds(60)
                .metricsConfig(null)
                .dockerExecutionProfileId(profileId)
                .build();
        when(taskRepository.findById(taskId))
                .thenReturn(Optional.of(task), Optional.of(task), Optional.empty());

        when(historyRepository.findById(taskId))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(TestTaskHistoryEntity.builder()
                        .id(taskId)
                        .finalStatus("PROCESSING")
                        .createdAt(task.getCreatedAt())
                        .movedAt(OffsetDateTime.now())
                        .testTool("k6")
                        .testFileName("t.js")
                        .testFileContentBase64("YQ==")
                        .command("run")
                        .expectedDurationSeconds(60)
                        .dockerExecutionProfileId(profileId)
                        .build()));

        when(dockerExecutionProfileRepository.findById(profileId))
                .thenReturn(Optional.of(DockerExecutionProfileEntity.builder()
                        .id(profileId)
                        .name("default")
                        .enabled(true)
                        .maxConcurrentContainers(4)
                        .build()));

        ExecutionResponse resp = new ExecutionResponse("success", null, null, null, null, 3L, null, null);
        when(processor.process(any(TestTaskMessage.class)))
                .thenReturn(new TaskProcessOutcome(resp, 1L, 2L));

        TestTaskRunResult r = service.execute(new TestTaskEvent(taskId.toString()));

        assertThat(r.getKind()).isEqualTo(TestTaskRunResult.Kind.COMPLETED);
        verify(taskRepository, never()).save(any(TestTaskEntity.class));
        verify(taskRepository, never()).delete(any(TestTaskEntity.class));
    }

    @Test
    void sleepForDockerRetry_default_invokesThreadSleep() {
        long t0 = System.nanoTime();
        ReflectionTestUtils.invokeMethod(service, "sleepForDockerRetry");
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        assertThat(elapsedMs).isGreaterThanOrEqualTo(1900L);
    }
}
