package com.loadtest.app.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadtest.app.dto.SummarizationTaskEvent;
import com.loadtest.app.persistence.DockerExecutionProfileRepository;
import com.loadtest.app.persistence.SummarizerModelEntity;
import com.loadtest.app.persistence.SummarizerModelRepository;
import com.loadtest.app.persistence.TestArtifactEntity;
import com.loadtest.app.persistence.TestArtifactRepository;
import com.loadtest.app.persistence.TestMetricsEntity;
import com.loadtest.app.persistence.TestMetricsRepository;
import com.loadtest.app.persistence.TestSummaryEntity;
import com.loadtest.app.persistence.TestSummaryRepository;
import com.loadtest.app.persistence.TestTaskHistoryEntity;
import com.loadtest.app.persistence.TestTaskHistoryRepository;
import com.loadtest.app.persistence.TestTaskRepository;
import com.loadtest.app.service.ExternalLlmDispatchService;
import com.loadtest.app.service.ExternalSummarizationCallbackService;
import com.loadtest.app.service.KafkaOutboxService;
import com.loadtest.app.service.QueuePauseService;
import com.loadtest.app.service.TestQueueService;
import org.junit.jupiter.api.Test;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = TasksController.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TasksControllerExtendedWebMvcTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private DockerExecutionProfileRepository dockerExecutionProfileRepository;
    @MockBean
    private TestTaskRepository taskRepository;
    @MockBean
    private TestTaskHistoryRepository historyRepository;
    @MockBean
    private TestArtifactRepository artifactRepository;
    @MockBean
    private TestMetricsRepository metricsRepository;
    @MockBean
    private TestSummaryRepository summaryRepository;
    @MockBean
    private SummarizerModelRepository summarizerModelRepository;
    @MockBean
    private ExternalSummarizationCallbackService externalSummarizationCallbackService;
    @MockBean
    private ExternalLlmDispatchService externalLlmDispatchService;
    @MockBean
    private KafkaOutboxService kafkaOutboxService;
    @MockBean
    private TestQueueService testQueueService;
    @MockBean
    private QueuePauseService queuePauseService;

    private static TestTaskHistoryEntity history(UUID id, String summarizer) {
        OffsetDateTime t = OffsetDateTime.parse("2024-01-01T00:00:00Z");
        return TestTaskHistoryEntity.builder()
                .id(id)
                .finalStatus("SUCCESS")
                .createdAt(t)
                .movedAt(t)
                .testTool("K6")
                .testFileName("f.js")
                .testFileContentBase64(Base64.getEncoder().encodeToString("x".getBytes(StandardCharsets.UTF_8)))
                .command("k6 run")
                .summarizerName(summarizer)
                .build();
    }

    @Test
    void metricsAndSummary_lists() throws Exception {
        UUID tid = UUID.randomUUID();
        when(metricsRepository.findByTaskIdOrderByCollectedAtAsc(tid)).thenReturn(List.of(
                TestMetricsEntity.builder()
                        .id(UUID.randomUUID())
                        .taskId(tid)
                        .sourceType("PROM")
                        .endpointUrl("http://m")
                        .metricsData("{\"v\":1}")
                        .collectedAt(OffsetDateTime.MIN)
                        .build()));
        mockMvc.perform(get("/api/v1/loadtest/metrics/{taskId}", tid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sourceType").value("PROM"));

        when(summaryRepository.findByTaskIdOrderByProcessedAtDesc(tid)).thenReturn(List.of(
                TestSummaryEntity.builder()
                        .id(UUID.randomUUID())
                        .taskId(tid)
                        .summaryType("AI")
                        .summaryData("{\"a\":1}")
                        .processingStatus("DONE")
                        .createdAt(OffsetDateTime.MIN)
                        .build()));
        mockMvc.perform(get("/api/v1/loadtest/history/{taskId}/summary", tid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].summaryType").value("AI"));
    }

    @Test
    void metricsAndSummary_parseFallbackBranches() throws Exception {
        UUID tid = UUID.randomUUID();
        when(metricsRepository.findByTaskIdOrderByCollectedAtAsc(tid)).thenReturn(List.of(
                TestMetricsEntity.builder()
                        .id(UUID.randomUUID())
                        .taskId(tid)
                        .sourceType("PROM")
                        .endpointUrl("http://m")
                        .metricsData("not-json")
                        .collectedAt(OffsetDateTime.MIN)
                        .build()));
        mockMvc.perform(get("/api/v1/loadtest/metrics/{taskId}", tid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].metricsData").value("not-json"));

        when(summaryRepository.findByTaskIdOrderByProcessedAtDesc(tid)).thenReturn(List.of(
                TestSummaryEntity.builder()
                        .id(UUID.randomUUID())
                        .taskId(tid)
                        .summaryType("AI")
                        .summaryData("\"{\\\"inner\\\":1}\"")
                        .processingStatus("DONE")
                        .createdAt(OffsetDateTime.MIN)
                        .build()));
        mockMvc.perform(get("/api/v1/loadtest/history/{taskId}/summary", tid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].summaryData.inner").value(1));
    }

    @Test
    void requestSummarization_branches() throws Exception {
        UUID tid = UUID.randomUUID();
        when(historyRepository.findById(tid)).thenReturn(Optional.empty());
        mockMvc.perform(post("/api/v1/loadtest/history/{taskId}/summarize", tid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());

        when(historyRepository.findById(tid)).thenReturn(Optional.of(history(tid, null)));
        mockMvc.perform(post("/api/v1/loadtest/history/{taskId}/summarize", tid))
                .andExpect(status().isBadRequest());

        when(historyRepository.findById(tid)).thenReturn(Optional.of(history(tid, "r1")));
        when(summarizerModelRepository.findByName("r1")).thenReturn(Optional.empty());
        mockMvc.perform(post("/api/v1/loadtest/history/{taskId}/summarize", tid))
                .andExpect(status().isBadRequest());

        SummarizerModelEntity disabled = SummarizerModelEntity.builder()
                .id(UUID.randomUUID())
                .name("r1")
                .provider("OPENAI")
                .modelId("m")
                .enabled(false)
                .createdAt(OffsetDateTime.MIN)
                .updatedAt(OffsetDateTime.MIN)
                .build();
        when(summarizerModelRepository.findByName("r1")).thenReturn(Optional.of(disabled));
        mockMvc.perform(post("/api/v1/loadtest/history/{taskId}/summarize", tid))
                .andExpect(status().isBadRequest());

        SummarizerModelEntity extNoUrl = SummarizerModelEntity.builder()
                .id(UUID.randomUUID())
                .name("r1")
                .provider("EXTERNAL")
                .modelId("m")
                .baseUrl("  ")
                .enabled(true)
                .createdAt(OffsetDateTime.MIN)
                .updatedAt(OffsetDateTime.MIN)
                .build();
        when(summarizerModelRepository.findByName("r1")).thenReturn(Optional.of(extNoUrl));
        mockMvc.perform(post("/api/v1/loadtest/history/{taskId}/summarize", tid))
                .andExpect(status().isBadRequest());

        SummarizerModelEntity openAi = SummarizerModelEntity.builder()
                .id(UUID.randomUUID())
                .name("r1")
                .provider("OPENAI")
                .modelId("m")
                .enabled(true)
                .createdAt(OffsetDateTime.MIN)
                .updatedAt(OffsetDateTime.MIN)
                .build();
        when(summarizerModelRepository.findByName("r1")).thenReturn(Optional.of(openAi));
        when(historyRepository.save(any(TestTaskHistoryEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        mockMvc.perform(post("/api/v1/loadtest/history/{taskId}/summarize", tid))
                .andExpect(status().isAccepted());
        verify(kafkaOutboxService).sendSummarizationTaskEvent(eq(tid.toString()), any(SummarizationTaskEvent.class));

        SummarizerModelEntity extOk = SummarizerModelEntity.builder()
                .id(UUID.randomUUID())
                .name("r1")
                .provider("EXTERNAL")
                .modelId("m")
                .baseUrl("https://ingest.example/hook")
                .enabled(true)
                .createdAt(OffsetDateTime.MIN)
                .updatedAt(OffsetDateTime.MIN)
                .build();
        when(summarizerModelRepository.findByName("r1")).thenReturn(Optional.of(extOk));
        doNothing().when(externalSummarizationCallbackService).registerPendingWindow(eq(tid), eq("r1"));
        when(externalLlmDispatchService.dispatchPackage(tid)).thenReturn(Map.of("status", "success"));
        mockMvc.perform(post("/api/v1/loadtest/history/{taskId}/summarize", tid))
                .andExpect(status().isAccepted());

        when(externalLlmDispatchService.dispatchPackage(tid))
                .thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.BAD_GATEWAY, "down"));
        mockMvc.perform(post("/api/v1/loadtest/history/{taskId}/summarize", tid))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value("down"));
    }

    @Test
    void externalLlmEndpoints() throws Exception {
        UUID tid = UUID.randomUUID();
        when(externalSummarizationCallbackService.buildPackage(tid)).thenReturn(Map.of("taskId", tid.toString()));
        mockMvc.perform(get("/api/v1/loadtest/history/{taskId}/external-llm/package", tid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(tid.toString()));

        when(externalLlmDispatchService.dispatchPackage(tid)).thenReturn(Map.of("ok", true));
        mockMvc.perform(post("/api/v1/loadtest/history/{taskId}/external-llm/dispatch", tid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        doNothing().when(externalSummarizationCallbackService).submitExternalSummary(eq(tid), any());
        mockMvc.perform(post("/api/v1/loadtest/history/{taskId}/external-llm/summary", tid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"hello\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"));
    }

    @Test
    void summarize_externalDispatchWithoutReason_usesFallbackMessage() throws Exception {
        UUID tid = UUID.randomUUID();
        when(historyRepository.findById(tid)).thenReturn(Optional.of(history(tid, "r1")));
        when(historyRepository.save(any(TestTaskHistoryEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(summarizerModelRepository.findByName("r1")).thenReturn(Optional.of(
                SummarizerModelEntity.builder()
                        .id(UUID.randomUUID())
                        .name("r1")
                        .provider("EXTERNAL")
                        .modelId("m")
                        .baseUrl("https://ingest.example/hook")
                        .enabled(true)
                        .createdAt(OffsetDateTime.MIN)
                        .updatedAt(OffsetDateTime.MIN)
                        .build()));
        doNothing().when(externalSummarizationCallbackService).registerPendingWindow(eq(tid), eq("r1"));
        when(externalLlmDispatchService.dispatchPackage(tid))
                .thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.BAD_GATEWAY));
        mockMvc.perform(post("/api/v1/loadtest/history/{taskId}/summarize", tid))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Не удалось отправить пакет")));
    }

    @Test
    void artifacts_listAndDownload() throws Exception {
        UUID tid = UUID.randomUUID();
        when(artifactRepository.findByTaskIdOrderByFileName(tid)).thenReturn(List.of(
                TestArtifactEntity.builder()
                        .id(UUID.randomUUID())
                        .taskId(tid)
                        .fileName("r.html")
                        .fileContent("<html/>".getBytes(StandardCharsets.UTF_8))
                        .contentEncoding("identity")
                        .createdAt(OffsetDateTime.MIN)
                        .build()));
        mockMvc.perform(get("/api/v1/loadtest/artifacts/{taskId}", tid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fileName").value("r.html"));

        when(artifactRepository.findByTaskIdAndFileName(tid, "missing.bin")).thenReturn(Optional.empty());
        mockMvc.perform(get("/api/v1/loadtest/artifacts/{taskId}/files/{fileName}", tid, "missing.bin"))
                .andExpect(status().isNotFound());

        when(artifactRepository.findByTaskIdAndFileName(tid, "r.csv")).thenReturn(Optional.of(
                TestArtifactEntity.builder()
                        .id(UUID.randomUUID())
                        .taskId(tid)
                        .fileName("r.csv")
                        .fileContent("a,b".getBytes(StandardCharsets.UTF_8))
                        .contentEncoding("identity")
                        .createdAt(OffsetDateTime.MIN)
                        .build()));
        mockMvc.perform(get("/api/v1/loadtest/artifacts/{taskId}/files/{fileName}", tid, "r.csv"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, org.hamcrest.Matchers.containsString("csv")));
    }

    @Test
    void historyDelete_rerun_deleteTaskOutcomes() throws Exception {
        UUID tid = UUID.randomUUID();
        when(testQueueService.deleteHistoryRun(tid)).thenReturn(false);
        mockMvc.perform(delete("/api/v1/loadtest/history/{taskId}", tid))
                .andExpect(status().isNotFound());
        when(testQueueService.deleteHistoryRun(tid)).thenReturn(true);
        mockMvc.perform(delete("/api/v1/loadtest/history/{taskId}", tid))
                .andExpect(status().isNoContent());

        when(historyRepository.findById(tid)).thenReturn(Optional.empty());
        mockMvc.perform(post("/api/v1/loadtest/history/{taskId}/rerun", tid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());

        when(historyRepository.findById(tid)).thenReturn(Optional.of(history(tid, "s")));
        when(summarizerModelRepository.findByName("bad")).thenReturn(Optional.empty());
        mockMvc.perform(post("/api/v1/loadtest/history/{taskId}/rerun", tid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("summarizer", "bad"))))
                .andExpect(status().isBadRequest());

        when(historyRepository.findById(tid)).thenReturn(Optional.of(history(tid, "s")));
        when(testQueueService.rerunFromHistory(eq(tid), isNull())).thenReturn("new-id");
        mockMvc.perform(post("/api/v1/loadtest/history/{taskId}/rerun", tid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value("new-id"));

        when(historyRepository.findById(tid)).thenReturn(Optional.of(history(tid, "s")));
        when(testQueueService.rerunFromHistory(eq(tid), isNull())).thenThrow(new IllegalArgumentException("gone"));
        mockMvc.perform(post("/api/v1/loadtest/history/{taskId}/rerun", tid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());

        when(historyRepository.findById(tid)).thenReturn(Optional.of(history(tid, "s")));
        when(summarizerModelRepository.findByName("off")).thenReturn(Optional.of(
                SummarizerModelEntity.builder()
                        .id(UUID.randomUUID())
                        .name("off")
                        .provider("OPENAI")
                        .modelId("m")
                        .enabled(false)
                        .createdAt(OffsetDateTime.MIN)
                        .updatedAt(OffsetDateTime.MIN)
                        .build()));
        mockMvc.perform(post("/api/v1/loadtest/history/{taskId}/rerun", tid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("summarizer", "off"))))
                .andExpect(status().isBadRequest());

        when(historyRepository.findById(tid)).thenReturn(Optional.of(history(tid, "s")));
        when(summarizerModelRepository.findByName("ext-empty")).thenReturn(Optional.of(
                SummarizerModelEntity.builder()
                        .id(UUID.randomUUID())
                        .name("ext-empty")
                        .provider("EXTERNAL")
                        .modelId("m")
                        .baseUrl(" ")
                        .enabled(true)
                        .createdAt(OffsetDateTime.MIN)
                        .updatedAt(OffsetDateTime.MIN)
                        .build()));
        mockMvc.perform(post("/api/v1/loadtest/history/{taskId}/rerun", tid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("summarizer", "ext-empty"))))
                .andExpect(status().isBadRequest());

        when(testQueueService.deletePendingQueueTask(tid)).thenReturn(TestQueueService.DeletePendingQueueTaskOutcome.NOT_FOUND);
        mockMvc.perform(delete("/api/v1/loadtest/tasks/{taskId}", tid))
                .andExpect(status().isNotFound());
        when(testQueueService.deletePendingQueueTask(tid)).thenReturn(TestQueueService.DeletePendingQueueTaskOutcome.NOT_DELETABLE);
        mockMvc.perform(delete("/api/v1/loadtest/tasks/{taskId}", tid))
                .andExpect(status().isConflict());
    }

    @Test
    void queuePause_putSupportsStringBoolean() throws Exception {
        when(queuePauseService.setPaused(true)).thenReturn(new QueuePauseService.QueuePauseState(true, 1));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/loadtest/queue/pause")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("paused", "true"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paused").value(true));
    }

    @Test
    void artifactDownload_gzipErrorReturns500() throws Exception {
        UUID tid = UUID.randomUUID();
        when(artifactRepository.findByTaskIdAndFileName(tid, "bad.xml")).thenReturn(Optional.of(
                TestArtifactEntity.builder()
                        .id(UUID.randomUUID())
                        .taskId(tid)
                        .fileName("bad.xml")
                        .fileContent(new byte[] {1, 2, 3})
                        .contentEncoding("gzip")
                        .createdAt(OffsetDateTime.MIN)
                        .build()));
        mockMvc.perform(get("/api/v1/loadtest/artifacts/{taskId}/files/{fileName}", tid, "bad.xml"))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void artifactContentTypeBranches() throws Exception {
        UUID tid = UUID.randomUUID();
        when(artifactRepository.findByTaskIdAndFileName(tid, "report.html")).thenReturn(Optional.of(
                TestArtifactEntity.builder().id(UUID.randomUUID()).taskId(tid).fileName("report.html")
                        .fileContent("h".getBytes(StandardCharsets.UTF_8)).contentEncoding("identity").createdAt(OffsetDateTime.MIN).build()));
        mockMvc.perform(get("/api/v1/loadtest/artifacts/{taskId}/files/{fileName}", tid, "report.html"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, org.hamcrest.Matchers.containsString("text/html")));

        when(artifactRepository.findByTaskIdAndFileName(tid, "m.json")).thenReturn(Optional.of(
                TestArtifactEntity.builder().id(UUID.randomUUID()).taskId(tid).fileName("m.json")
                        .fileContent("{}".getBytes(StandardCharsets.UTF_8)).contentEncoding("identity").createdAt(OffsetDateTime.MIN).build()));
        mockMvc.perform(get("/api/v1/loadtest/artifacts/{taskId}/files/{fileName}", tid, "m.json"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, org.hamcrest.Matchers.containsString("application/json")));

        when(artifactRepository.findByTaskIdAndFileName(tid, "x.xml")).thenReturn(Optional.of(
                TestArtifactEntity.builder().id(UUID.randomUUID()).taskId(tid).fileName("x.xml")
                        .fileContent("<x/>".getBytes(StandardCharsets.UTF_8)).contentEncoding("identity").createdAt(OffsetDateTime.MIN).build()));
        mockMvc.perform(get("/api/v1/loadtest/artifacts/{taskId}/files/{fileName}", tid, "x.xml"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, org.hamcrest.Matchers.containsString("application/xml")));

        when(artifactRepository.findByTaskIdAndFileName(tid, "blob.bin")).thenReturn(Optional.of(
                TestArtifactEntity.builder().id(UUID.randomUUID()).taskId(tid).fileName("blob.bin")
                        .fileContent(new byte[] {1}).contentEncoding("identity").createdAt(OffsetDateTime.MIN).build()));
        mockMvc.perform(get("/api/v1/loadtest/artifacts/{taskId}/files/{fileName}", tid, "blob.bin"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, org.hamcrest.Matchers.containsString("application/octet-stream")));
    }

    @Test
    void artifactDownload_gzipSuccess_andSummaryFallbackRawString() throws Exception {
        UUID tid = UUID.randomUUID();
        byte[] gz;
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             GZIPOutputStream gos = new GZIPOutputStream(bos)) {
            gos.write("ok".getBytes(StandardCharsets.UTF_8));
            gos.finish();
            gz = bos.toByteArray();
        }
        when(artifactRepository.findByTaskIdAndFileName(tid, "ok.json")).thenReturn(Optional.of(
                TestArtifactEntity.builder()
                        .id(UUID.randomUUID())
                        .taskId(tid)
                        .fileName("ok.json")
                        .fileContent(gz)
                        .contentEncoding("gzip")
                        .createdAt(OffsetDateTime.MIN)
                        .build()));
        mockMvc.perform(get("/api/v1/loadtest/artifacts/{taskId}/files/{fileName}", tid, "ok.json"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, org.hamcrest.Matchers.containsString("application/json")));

        when(summaryRepository.findByTaskIdOrderByProcessedAtDesc(tid)).thenReturn(List.of(
                TestSummaryEntity.builder()
                        .id(UUID.randomUUID())
                        .taskId(tid)
                        .summaryType("AI")
                        .summaryData("{invalid-json")
                        .processingStatus("DONE")
                        .createdAt(OffsetDateTime.MIN)
                        .build()));
        mockMvc.perform(get("/api/v1/loadtest/history/{taskId}/summary", tid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].summaryData").value("{invalid-json"));
    }
}
