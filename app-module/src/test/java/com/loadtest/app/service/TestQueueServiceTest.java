package com.loadtest.app.service;

import com.loadtest.app.dto.TestTaskMessage;
import com.loadtest.app.util.NativeQueryParams;
import com.loadtest.app.persistence.DockerExecutionProfileEntity;
import com.loadtest.app.persistence.DockerExecutionProfileRepository;
import com.loadtest.app.persistence.TestTaskHistoryEntity;
import com.loadtest.app.persistence.TestTaskHistoryRepository;
import com.loadtest.app.persistence.TestTaskRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TestQueueServiceTest {

    @Mock
    private EntityManager entityManager;
    @Mock
    private KafkaOutboxService kafkaOutboxService;
    @Mock
    private QueuePauseService queuePauseService;
    @Mock
    private TestTaskHistoryRepository historyRepository;
    @Mock
    private TestTaskRepository taskRepository;
    @Mock
    private DockerExecutionProfileRepository dockerExecutionProfileRepository;

    private TestQueueService service;

    @BeforeEach
    void setUp() {
        service = new TestQueueService(
                entityManager,
                kafkaOutboxService,
                queuePauseService,
                historyRepository,
                taskRepository,
                dockerExecutionProfileRepository);
        ReflectionTestUtils.setField(service, "testTasksTopic", "test-tasks");
    }

    @Test
    void deletePendingQueueTask_outcomes() {
        UUID id = UUID.randomUUID();
        when(taskRepository.deleteByIdIfStatusPending(id)).thenReturn(1);
        assertThat(service.deletePendingQueueTask(id)).isEqualTo(TestQueueService.DeletePendingQueueTaskOutcome.DELETED);

        when(taskRepository.deleteByIdIfStatusPending(id)).thenReturn(0);
        when(taskRepository.existsById(id)).thenReturn(false);
        assertThat(service.deletePendingQueueTask(id)).isEqualTo(TestQueueService.DeletePendingQueueTaskOutcome.NOT_FOUND);

        when(taskRepository.existsById(id)).thenReturn(true);
        assertThat(service.deletePendingQueueTask(id)).isEqualTo(TestQueueService.DeletePendingQueueTaskOutcome.NOT_DELETABLE);
    }

    @Test
    void deleteHistoryRun() {
        UUID id = UUID.randomUUID();
        when(historyRepository.existsById(id)).thenReturn(false);
        assertThat(service.deleteHistoryRun(id)).isFalse();
        when(historyRepository.existsById(id)).thenReturn(true);
        assertThat(service.deleteHistoryRun(id)).isTrue();
        verify(historyRepository).deleteById(id);
    }

    @Test
    void rerunFromHistory_persistsTaskFromHistory() {
        UUID hid = UUID.randomUUID();
        UUID pid = UUID.randomUUID();
        OffsetDateTime t = OffsetDateTime.parse("2024-01-01T00:00:00Z");
        TestTaskHistoryEntity hist = historyEntity(hid, t, 120, "route", pid, null);
        when(historyRepository.findById(hid)).thenReturn(Optional.of(hist));
        Query q = stubInsertQuery();

        try (var tsm = Mockito.mockStatic(TransactionSynchronizationManager.class)) {
            tsm.when(TransactionSynchronizationManager::isSynchronizationActive).thenReturn(true);
            tsm.when(() -> TransactionSynchronizationManager.registerSynchronization(any())).thenAnswer(inv -> null);
            assertThat(service.rerunFromHistory(hid, null)).isNotBlank();
            verify(q).setParameter(eq(NativeQueryParams.TEST_TOOL), eq("K6"));
            verify(q).setParameter(eq(NativeQueryParams.EXPECTED_DURATION_SECONDS), eq(120));
            verify(q).setParameter(eq(NativeQueryParams.SUMMARIZER_NAME), eq("route"));
            verify(q).setParameter(eq(NativeQueryParams.DOCKER_PROFILE_ID), eq(pid));
        }
    }

    @Test
    void rerunFromHistory_summarizerOverride() {
        UUID hid = UUID.randomUUID();
        UUID pid = UUID.randomUUID();
        OffsetDateTime t = OffsetDateTime.parse("2024-01-01T00:00:00Z");
        TestTaskHistoryEntity hist = historyEntity(hid, t, null, "route", pid, null);
        when(historyRepository.findById(hid)).thenReturn(Optional.of(hist));
        Query q = stubInsertQuery();

        try (var tsm = Mockito.mockStatic(TransactionSynchronizationManager.class)) {
            tsm.when(TransactionSynchronizationManager::isSynchronizationActive).thenReturn(true);
            tsm.when(() -> TransactionSynchronizationManager.registerSynchronization(any())).thenAnswer(inv -> null);
            assertThat(service.rerunFromHistory(hid, "override")).isNotBlank();
            verify(q).setParameter(eq(NativeQueryParams.EXPECTED_DURATION_SECONDS), eq(60));
            verify(q).setParameter(eq(NativeQueryParams.SUMMARIZER_NAME), eq("override"));
        }
    }

    @Test
    void rerunFromHistory_notFound() {
        UUID hid = UUID.randomUUID();
        when(historyRepository.findById(hid)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.rerunFromHistory(hid, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void enqueueTest_afterCommit_sendsKafkaWhenUnpaused() {
        runEnqueueAfterCommit(false);
        verify(kafkaOutboxService).sendTestTaskEvent(anyString(), any());
    }

    @Test
    void enqueueTest_afterCommit_recordsPendingWhenPaused() {
        runEnqueueAfterCommit(true);
        verify(queuePauseService).recordPendingKafkaDispatch(any(UUID.class));
    }

    @Test
    void enqueueTest_usesFallbackFirstEnabledProfile() {
        Query q = mock(Query.class);
        when(entityManager.createNativeQuery(contains("INSERT INTO test_task"))).thenReturn(q);
        when(q.setParameter(anyString(), any())).thenReturn(q);
        when(q.executeUpdate()).thenReturn(1);
        UUID fallbackId = UUID.randomUUID();
        when(dockerExecutionProfileRepository.findFirstByNameAndEnabledTrue(anyString())).thenReturn(Optional.empty());
        when(dockerExecutionProfileRepository.findFirstByEnabledTrueOrderByCreatedAtAsc())
                .thenReturn(Optional.of(DockerExecutionProfileEntity.builder()
                        .id(fallbackId).name("f").enabled(true).maxConcurrentContainers(1)
                        .createdAt(OffsetDateTime.MIN).updatedAt(OffsetDateTime.MIN).build()));

        try (var tsm = Mockito.mockStatic(TransactionSynchronizationManager.class)) {
            tsm.when(TransactionSynchronizationManager::isSynchronizationActive).thenReturn(true);
            tsm.when(() -> TransactionSynchronizationManager.registerSynchronization(any())).thenAnswer(inv -> null);
            service.enqueueTest("K6", "f.js", "QQ==", "run", 10, null, null, null, null);
            verify(q).setParameter(eq(NativeQueryParams.DOCKER_PROFILE_ID), eq(fallbackId));
        }
    }

    @Test
    void enqueueTest_noProfiles_throwsIllegalState() {
        when(dockerExecutionProfileRepository.findFirstByNameAndEnabledTrue(anyString())).thenReturn(Optional.empty());
        when(dockerExecutionProfileRepository.findFirstByEnabledTrueOrderByCreatedAtAsc()).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.enqueueTest("K6", "f.js", "QQ==", "run", 10, null, null, null, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rerunFromHistory_blankOverrideUsesHistory() {
        UUID hid = UUID.randomUUID();
        UUID pid = UUID.randomUUID();
        OffsetDateTime t = OffsetDateTime.parse("2024-01-01T00:00:00Z");
        TestTaskHistoryEntity hist = historyEntity(hid, t, 60, "history-route", pid, "{}");
        when(historyRepository.findById(hid)).thenReturn(Optional.of(hist));
        Query q = stubInsertQuery();

        try (var tsm = Mockito.mockStatic(TransactionSynchronizationManager.class)) {
            tsm.when(TransactionSynchronizationManager::isSynchronizationActive).thenReturn(true);
            tsm.when(() -> TransactionSynchronizationManager.registerSynchronization(any())).thenAnswer(inv -> null);
            assertThat(service.rerunFromHistory(hid, "   ")).isNotBlank();
            verify(q).setParameter(eq(NativeQueryParams.METRICS_CONFIG), eq("{}"));
            verify(q).setParameter(eq(NativeQueryParams.SUMMARIZER_NAME), eq("history-route"));
        }
    }

    @Test
    void enqueueTest_withMetricsAndNullCommand_andAfterCommitError() {
        Query q = mock(Query.class);
        when(entityManager.createNativeQuery(contains("INSERT INTO test_task"))).thenReturn(q);
        when(q.setParameter(anyString(), any())).thenReturn(q);
        when(q.executeUpdate()).thenReturn(1);
        doThrow(new RuntimeException("kafka down")).when(kafkaOutboxService).sendTestTaskEvent(anyString(), any());
        UUID providedProfile = UUID.randomUUID();

        TestTaskMessage.MetricsConfig.MetricsRequest req = new TestTaskMessage.MetricsConfig.MetricsRequest(
                "r1", "GET", "http://m", null, null, null);
        TestTaskMessage.MetricsConfig cfg = new TestTaskMessage.MetricsConfig(1, List.of(req));

        try (var tsm = Mockito.mockStatic(TransactionSynchronizationManager.class)) {
            AtomicReference<TransactionSynchronization> sync = new AtomicReference<>();
            tsm.when(TransactionSynchronizationManager::isSynchronizationActive).thenReturn(true);
            tsm.when(() -> TransactionSynchronizationManager.registerSynchronization(any()))
                    .thenAnswer(inv -> {
                        sync.set(inv.getArgument(0));
                        return null;
                    });
            when(queuePauseService.isQueuePaused()).thenReturn(false);

            service.enqueueTest("K6", "m.js", null, null, 30, cfg, "{\"a\":1}", "sum", providedProfile);
            verify(q).setParameter(eq(NativeQueryParams.DOCKER_PROFILE_ID), eq(providedProfile));
            verify(q).setParameter(eq(NativeQueryParams.COMMAND), eq(""));
            verify(q).setParameter(eq(NativeQueryParams.METRICS_CONFIG), eq("{\"a\":1}"));
            assertThat(sync.get()).isNotNull();
            sync.get().afterCommit();
        }
    }

    @Test
    void enqueueTest_metricsConfiguredWithNullRequests_coversMetricsBranch() {
        Query q = mock(Query.class);
        when(entityManager.createNativeQuery(contains("INSERT INTO test_task"))).thenReturn(q);
        when(q.setParameter(anyString(), any())).thenReturn(q);
        when(q.executeUpdate()).thenReturn(1);
        UUID providedProfile = UUID.randomUUID();

        TestTaskMessage.MetricsConfig cfg = new TestTaskMessage.MetricsConfig(2, null);

        try (var tsm = Mockito.mockStatic(TransactionSynchronizationManager.class)) {
            AtomicReference<TransactionSynchronization> sync = new AtomicReference<>();
            tsm.when(TransactionSynchronizationManager::isSynchronizationActive).thenReturn(true);
            tsm.when(() -> TransactionSynchronizationManager.registerSynchronization(any()))
                    .thenAnswer(inv -> {
                        sync.set(inv.getArgument(0));
                        return null;
                    });
            when(queuePauseService.isQueuePaused()).thenReturn(false);

            service.enqueueTest("K6", "n.js", "QQ==", "run", 30, cfg, null, null, providedProfile);
            verify(q).setParameter(eq(NativeQueryParams.METRICS_CONFIG), isNull());
            assertThat(sync.get()).isNotNull();
            sync.get().afterCommit();
        }
    }

    private Query stubInsertQuery() {
        Query q = mock(Query.class);
        when(entityManager.createNativeQuery(contains("INSERT INTO test_task"))).thenReturn(q);
        when(q.setParameter(anyString(), any())).thenReturn(q);
        when(q.executeUpdate()).thenReturn(1);
        return q;
    }

    private static TestTaskHistoryEntity historyEntity(UUID hid, OffsetDateTime t, Integer expectedDurationSeconds,
                                                       String summarizerName, UUID profileId, String metricsConfig) {
        return TestTaskHistoryEntity.builder()
                .id(hid)
                .finalStatus("OK")
                .createdAt(t)
                .movedAt(t)
                .testTool("K6")
                .testFileName("f.js")
                .testFileContentBase64("QQ==")
                .command("run")
                .expectedDurationSeconds(expectedDurationSeconds)
                .summarizerName(summarizerName)
                .dockerExecutionProfileId(profileId)
                .metricsConfig(metricsConfig)
                .build();
    }

    private void runEnqueueAfterCommit(boolean paused) {
        stubInsertQuery();
        UUID profileId = UUID.randomUUID();
        when(dockerExecutionProfileRepository.findFirstByNameAndEnabledTrue(anyString()))
                .thenReturn(Optional.of(DockerExecutionProfileEntity.builder()
                        .id(profileId)
                        .name("Default")
                        .enabled(true)
                        .maxConcurrentContainers(1)
                        .createdAt(OffsetDateTime.MIN)
                        .updatedAt(OffsetDateTime.MIN)
                        .build()));

        try (var tsm = Mockito.mockStatic(TransactionSynchronizationManager.class)) {
            AtomicReference<TransactionSynchronization> sync = new AtomicReference<>();
            tsm.when(TransactionSynchronizationManager::isSynchronizationActive).thenReturn(true);
            tsm.when(() -> TransactionSynchronizationManager.registerSynchronization(any()))
                    .thenAnswer(inv -> {
                        sync.set(inv.getArgument(0));
                        return null;
                    });
            when(queuePauseService.isQueuePaused()).thenReturn(paused);
            service.enqueueTest("K6", "f.js", "QQ==", "run", 10, null, null, null, null);
            assertThat(sync.get()).isNotNull();
            sync.get().afterCommit();
        }
    }
}
