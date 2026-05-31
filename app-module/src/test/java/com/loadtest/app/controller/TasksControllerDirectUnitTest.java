package com.loadtest.app.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadtest.app.persistence.SummarizerModelEntity;
import com.loadtest.app.persistence.SummarizerModelRepository;
import com.loadtest.app.persistence.TestTaskHistoryEntity;
import com.loadtest.app.persistence.TestTaskHistoryRepository;
import com.loadtest.app.service.CustomSummarizationPromptStore;
import com.loadtest.app.service.ExternalLlmDispatchService;
import com.loadtest.app.service.ExternalSummarizationCallbackService;
import com.loadtest.app.service.KafkaOutboxService;
import com.loadtest.app.service.QueuePauseService;
import com.loadtest.app.service.TestQueueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TasksControllerDirectUnitTest {

    private TestTaskHistoryRepository historyRepository;
    private SummarizerModelRepository summarizerModelRepository;
    private ExternalSummarizationCallbackService externalSummarizationCallbackService;
    private ExternalLlmDispatchService externalLlmDispatchService;
    private TestQueueService testQueueService;
    private QueuePauseService queuePauseService;
    private TasksController controller;

    @BeforeEach
    void setUp() {
        historyRepository = mock(TestTaskHistoryRepository.class);
        summarizerModelRepository = mock(SummarizerModelRepository.class);
        externalSummarizationCallbackService = mock(ExternalSummarizationCallbackService.class);
        externalLlmDispatchService = mock(ExternalLlmDispatchService.class);
        testQueueService = mock(TestQueueService.class);
        queuePauseService = mock(QueuePauseService.class);
        controller = new TasksController(
                mock(com.loadtest.app.persistence.DockerExecutionProfileRepository.class),
                mock(com.loadtest.app.persistence.TestTaskRepository.class),
                historyRepository,
                mock(com.loadtest.app.persistence.TestArtifactRepository.class),
                mock(com.loadtest.app.persistence.TestMetricsRepository.class),
                mock(com.loadtest.app.persistence.TestSummaryRepository.class),
                summarizerModelRepository,
                externalSummarizationCallbackService,
                externalLlmDispatchService,
                new CustomSummarizationPromptStore(),
                mock(KafkaOutboxService.class),
                testQueueService,
                queuePauseService,
                new ObjectMapper());
    }

    private static TestTaskHistoryEntity history(UUID id, String summarizer) {
        return TestTaskHistoryEntity.builder()
                .id(id)
                .finalStatus("OK")
                .createdAt(OffsetDateTime.MIN)
                .movedAt(OffsetDateTime.MIN)
                .testTool("K6")
                .testFileName("f.js")
                .testFileContentBase64("QQ==")
                .command("run")
                .summarizerName(summarizer)
                .build();
    }

    @Test
    void queuePauseAndDeleteQueuedTask_branches() {
        ResponseEntity<Map<String, Object>> bad = controller.setQueuePause(null);
        assertThat(bad.getStatusCode().value()).isEqualTo(400);

        when(queuePauseService.setPaused(false)).thenReturn(new QueuePauseService.QueuePauseState(false, 0));
        ResponseEntity<Map<String, Object>> ok = controller.setQueuePause(Map.of("paused", "false"));
        assertThat(ok.getStatusCode().value()).isEqualTo(200);

        UUID id = UUID.randomUUID();
        when(testQueueService.deletePendingQueueTask(id)).thenReturn(TestQueueService.DeletePendingQueueTaskOutcome.DELETED);
        assertThat(controller.deleteQueuedTask(id).getStatusCode().value()).isEqualTo(200);
        when(testQueueService.deletePendingQueueTask(id)).thenReturn(TestQueueService.DeletePendingQueueTaskOutcome.NOT_FOUND);
        assertThat(controller.deleteQueuedTask(id).getStatusCode().value()).isEqualTo(404);
        when(testQueueService.deletePendingQueueTask(id)).thenReturn(TestQueueService.DeletePendingQueueTaskOutcome.NOT_DELETABLE);
        assertThat(controller.deleteQueuedTask(id).getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void rerunFromHistory_andRequestSummarization_nullAndExternalBranches() {
        UUID id = UUID.randomUUID();
        when(historyRepository.findById(id)).thenReturn(Optional.of(history(id, null)));
        when(testQueueService.rerunFromHistory(eq(id), isNull())).thenReturn("new-id");

        ResponseEntity<Map<String, String>> rerunNoSumm = controller.rerunFromHistory(id, Map.of("summarizer", " "));
        assertThat(rerunNoSumm.getStatusCode().value()).isEqualTo(200);

        when(historyRepository.findById(id)).thenReturn(Optional.of(history(id, "ext")));
        when(summarizerModelRepository.findByName("ext")).thenReturn(Optional.of(
                SummarizerModelEntity.builder()
                        .id(UUID.randomUUID())
                        .name("ext")
                        .provider("EXTERNAL")
                        .modelId("m")
                        .baseUrl(null)
                        .enabled(true)
                        .createdAt(OffsetDateTime.MIN)
                        .updatedAt(OffsetDateTime.MIN)
                        .build()));
        ResponseEntity<Map<String, String>> rerunExtNullUrl = controller.rerunFromHistory(id, Map.of("summarizer", "ext"));
        assertThat(rerunExtNullUrl.getStatusCode().value()).isEqualTo(400);

        when(historyRepository.findById(id)).thenReturn(Optional.of(history(id, null)));
        ResponseEntity<Map<String, String>> summarizeNoRoute = controller.requestSummarization(id, Map.of("summarizer", " "));
        assertThat(summarizeNoRoute.getStatusCode().value()).isEqualTo(400);

        when(historyRepository.findById(id)).thenReturn(Optional.of(history(id, "ext")));
        when(historyRepository.save(any(TestTaskHistoryEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(summarizerModelRepository.findByName("ext")).thenReturn(Optional.of(
                SummarizerModelEntity.builder()
                        .id(UUID.randomUUID())
                        .name("ext")
                        .provider("EXTERNAL")
                        .modelId("m")
                        .baseUrl(null)
                        .enabled(true)
                        .createdAt(OffsetDateTime.MIN)
                        .updatedAt(OffsetDateTime.MIN)
                        .build()));
        ResponseEntity<Map<String, String>> summarizeExtNoUrl = controller.requestSummarization(id, null);
        assertThat(summarizeExtNoUrl.getStatusCode().value()).isEqualTo(400);

        when(summarizerModelRepository.findByName("ext")).thenReturn(Optional.of(
                SummarizerModelEntity.builder()
                        .id(UUID.randomUUID())
                        .name("ext")
                        .provider("EXTERNAL")
                        .modelId("m")
                        .baseUrl("http://ingest")
                        .enabled(true)
                        .createdAt(OffsetDateTime.MIN)
                        .updatedAt(OffsetDateTime.MIN)
                        .build()));
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.BAD_GATEWAY, " "))
                .when(externalLlmDispatchService).dispatchPackage(eq(id), isNull());
        ResponseEntity<Map<String, String>> summarizeDispatchBlankReason = controller.requestSummarization(id, null);
        assertThat(summarizeDispatchBlankReason.getStatusCode().value()).isEqualTo(502);
    }

    @Test
    void submitExternalSummary_nullBody_branch() {
        UUID id = UUID.randomUUID();
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Поле text обязательно"))
                .when(externalSummarizationCallbackService).submitExternalSummary(eq(id), isNull());
        assertThatThrownBy(() -> controller.submitExternalLlmSummary(id, null))
                .isInstanceOf(ResponseStatusException.class);
    }
}

