package com.loadtest.app.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadtest.app.dto.ArtifactInfoDto;
import com.loadtest.app.dto.MetricsItemDto;
import com.loadtest.app.dto.SummaryItemDto;
import com.loadtest.app.dto.SummarizationTaskEvent;
import com.loadtest.app.dto.TaskHistoryItemDto;
import com.loadtest.app.dto.TaskQueueItemDto;
import com.loadtest.app.persistence.DockerExecutionProfileEntity;
import com.loadtest.app.persistence.DockerExecutionProfileRepository;
import com.loadtest.app.persistence.TestArtifactEntity;
import com.loadtest.app.persistence.TestArtifactRepository;
import com.loadtest.app.persistence.TestMetricsEntity;
import com.loadtest.app.persistence.TestMetricsRepository;
import com.loadtest.app.persistence.SummarizerModelEntity;
import com.loadtest.app.persistence.SummarizerModelRepository;
import com.loadtest.app.persistence.TestSummaryEntity;
import com.loadtest.app.persistence.TestSummaryRepository;
import com.loadtest.app.persistence.TestTaskEntity;
import com.loadtest.app.persistence.TestTaskHistoryEntity;
import com.loadtest.app.persistence.TestTaskHistoryRepository;
import com.loadtest.app.persistence.TestTaskRepository;
import com.loadtest.app.service.CustomSummarizationPromptStore;
import com.loadtest.app.service.ExternalLlmDispatchService;
import com.loadtest.app.service.ExternalSummarizationCallbackService;
import com.loadtest.app.service.KafkaOutboxService;
import com.loadtest.app.service.QueuePauseService;
import com.loadtest.app.service.TestQueueService;
import com.loadtest.app.util.ApiJsonKeys;
import com.loadtest.app.util.ApiMessages;
import com.loadtest.app.util.ApiResponseValues;
import com.loadtest.app.util.RequestBodyHelper;
import com.loadtest.app.util.ResponseHelper;
import com.loadtest.app.util.SummarizerProviders;
import com.loadtest.app.util.SummarizerRouteMessages;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

@Slf4j
@RestController
@RequestMapping("/api/v1/loadtest")
@RequiredArgsConstructor
public class TasksController {

    private final DockerExecutionProfileRepository dockerExecutionProfileRepository;
    private final TestTaskRepository taskRepository;
    private final TestTaskHistoryRepository historyRepository;
    private final TestArtifactRepository artifactRepository;
    private final TestMetricsRepository metricsRepository;
    private final TestSummaryRepository summaryRepository;
    private final SummarizerModelRepository summarizerModelRepository;
    private final ExternalSummarizationCallbackService externalSummarizationCallbackService;
    private final ExternalLlmDispatchService externalLlmDispatchService;
    private final CustomSummarizationPromptStore customSummarizationPromptStore;
    private final KafkaOutboxService kafkaOutboxService;
    private final TestQueueService testQueueService;
    private final QueuePauseService queuePauseService;
    private final ObjectMapper objectMapper;

    @GetMapping("/queue/pause")
    public Map<String, Object> getQueuePause() {
        var s = queuePauseService.getState();
        return Map.of(
                ApiJsonKeys.PAUSED, s.paused(),
                ApiJsonKeys.PENDING_KAFKA_DISPATCH_COUNT, s.pendingKafkaDispatchCount());
    }

    @PutMapping("/queue/pause")
    public ResponseEntity<Map<String, Object>> setQueuePause(@RequestBody Map<String, Object> body) {
        if (body == null || !body.containsKey(ApiJsonKeys.PAUSED)) {
            return ResponseEntity.badRequest().body(Map.of(ApiJsonKeys.MESSAGE, ApiMessages.Tasks.QUEUE_PAUSE_BODY));
        }
        Object raw = body.get(ApiJsonKeys.PAUSED);
        boolean paused;
        if (raw instanceof Boolean b) {
            paused = b;
        } else {
            paused = Boolean.parseBoolean(String.valueOf(raw));
        }
        var s = queuePauseService.setPaused(paused);
        return ResponseEntity.ok(Map.of(
                ApiJsonKeys.PAUSED, s.paused(),
                ApiJsonKeys.PENDING_KAFKA_DISPATCH_COUNT, s.pendingKafkaDispatchCount()));
    }

    @GetMapping("/tasks")
    public Map<String, Object> getQueue(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 20);

        var p = taskRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(safePage, safeSize));
        List<TaskQueueItemDto> items = p.getContent().stream()
                .map(this::toQueueDto)
                .toList();

        return paginatedResponse(items, safePage, safeSize, p);
    }

    @DeleteMapping("/tasks/{taskId}")
    public ResponseEntity<Map<String, Object>> deleteQueuedTask(@PathVariable UUID taskId) {
        return switch (testQueueService.deletePendingQueueTask(taskId)) {
            case DELETED -> ResponseEntity.ok(ResponseHelper.simpleSuccessBody(ApiMessages.Tasks.QUEUE_TASK_DELETED));
            case NOT_FOUND -> ResponseEntity.notFound().build();
            case NOT_DELETABLE -> ResponseHelper.buildErrorResponse(HttpStatus.CONFLICT,
                    ApiMessages.Tasks.TASK_NOT_DELETABLE);
        };
    }

    @DeleteMapping("/history/{taskId}")
    public ResponseEntity<Void> deleteHistoryTask(@PathVariable UUID taskId) {
        if (!testQueueService.deleteHistoryRun(taskId)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/history")
    public Map<String, Object> getHistory(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 20);

        var p = historyRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(safePage, safeSize));
        List<TaskHistoryItemDto> items = p.getContent().stream()
                .map(this::toHistoryDtoWithContent)
                .toList();

        return paginatedResponse(items, safePage, safeSize, p);
    }

    @GetMapping("/history/{taskId}")
    public ResponseEntity<TaskHistoryItemDto> getHistoryItem(@PathVariable UUID taskId) {
        return historyRepository.findById(taskId)
                .map(this::toHistoryDtoWithContent)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/history/{taskId}/rerun")
    public ResponseEntity<Map<String, String>> rerunFromHistory(
            @PathVariable UUID taskId,
            @RequestBody(required = false) Map<String, Object> body) {
        TestTaskHistoryEntity hist = historyRepository.findById(taskId).orElse(null);
        if (hist == null) {
            return ResponseEntity.notFound().build();
        }
        String summarizerOverride = null;
        if (body != null && body.get(ApiJsonKeys.SUMMARIZER) != null) {
            String s = String.valueOf(body.get(ApiJsonKeys.SUMMARIZER)).trim();
            if (!s.isEmpty()) {
                summarizerOverride = s;
            }
        }
        if (summarizerOverride != null) {
            var route = summarizerModelRepository.findByName(summarizerOverride).orElse(null);
            ResponseEntity<Map<String, String>> routeError =
                    validateSummarizerRoute(route, summarizerOverride, SummarizerValidationContext.RERUN);
            if (routeError != null) {
                return routeError;
            }
        }
        String customPrompt = RequestBodyHelper.extractCustomPrompt(body);
        try {
            String newTaskId = testQueueService.rerunFromHistory(taskId, summarizerOverride);
            if (customPrompt != null) {
                customSummarizationPromptStore.put(UUID.fromString(newTaskId), customPrompt);
                log.info("Stored custom summarization prompt for rerun taskId={} (in-memory, not DB)", newTaskId);
            }
            return ResponseEntity.ok(Map.of(
                    ApiJsonKeys.STATUS, ApiResponseValues.STATUS_SUCCESS,
                    ApiJsonKeys.MESSAGE, ApiMessages.Tasks.TEST_QUEUED,
                    ApiJsonKeys.TASK_ID, newTaskId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/metrics/{taskId}")
    public ResponseEntity<List<MetricsItemDto>> listMetrics(@PathVariable UUID taskId) {
        List<MetricsItemDto> list = metricsRepository.findByTaskIdOrderByCollectedAtAsc(taskId).stream()
                .map(this::toMetricsDto)
                .toList();
        return ResponseEntity.ok(list);
    }

    @GetMapping("/history/{taskId}/summary")
    public ResponseEntity<List<SummaryItemDto>> listSummary(@PathVariable UUID taskId) {
        List<SummaryItemDto> list = summaryRepository.findByTaskIdOrderByProcessedAtDesc(taskId).stream()
                .map(this::toSummaryDto)
                .toList();
        return ResponseEntity.ok(list);
    }

    @GetMapping("/internal/custom-summarization-prompt/{taskId}")
    public ResponseEntity<Map<String, String>> consumeStoredCustomSummarizationPrompt(@PathVariable UUID taskId) {
        return customSummarizationPromptStore.consume(taskId)
                .map(p -> ResponseEntity.ok(Map.of(ApiJsonKeys.CUSTOM_PROMPT, p)))
                .orElse(ResponseEntity.noContent().build());
    }

    @PostMapping("/history/{taskId}/summarize")
    public ResponseEntity<Map<String, String>> requestSummarization(
            @PathVariable UUID taskId,
            @RequestBody(required = false) Map<String, Object> body) {
        TestTaskHistoryEntity history = historyRepository.findById(taskId).orElse(null);
        if (history == null) {
            return ResponseEntity.notFound().build();
        }
        String customPrompt = RequestBodyHelper.extractCustomPrompt(body);
        String summarizer = body != null && body.containsKey(ApiJsonKeys.SUMMARIZER)
                ? String.valueOf(body.get(ApiJsonKeys.SUMMARIZER))
                : history.getSummarizerName();
        if (summarizer == null || summarizer.isBlank()) {
            return ResponseEntity.badRequest().body(ResponseHelper.messageBody(SummarizerRouteMessages.SUMMARIZER_REQUIRED));
        }
        String summarizerTrim = summarizer.trim();
        history.setSummarizerName(summarizerTrim);
        historyRepository.save(history);

        SummarizerModelEntity route = summarizerModelRepository.findByName(summarizerTrim).orElse(null);
        ResponseEntity<Map<String, String>> routeError =
                validateSummarizerRoute(route, summarizerTrim, SummarizerValidationContext.SUMMARIZE);
        if (routeError != null) {
            return routeError;
        }

        if (SummarizerProviders.EXTERNAL.equalsIgnoreCase(route.getProvider())) {
            externalSummarizationCallbackService.registerPendingWindow(taskId, summarizerTrim);
            try {
                externalLlmDispatchService.dispatchPackage(taskId, customPrompt);
            } catch (ResponseStatusException e) {
                String msg = e.getReason();
                if (msg == null || msg.isBlank()) {
                    msg = ApiMessages.Tasks.EXTERNAL_DISPATCH_FAILED;
                }
                log.warn("External dispatch after UI summarize failed: taskId={}, httpStatus={}, message={}",
                        taskId, e.getStatusCode().value(), msg);
                return ResponseEntity.status(e.getStatusCode().value()).body(ResponseHelper.messageBody(msg));
            }
            log.info("External summarization: window opened and package dispatched from UI: taskId={}, summarizer={}",
                    taskId, summarizerTrim);
            return ResponseEntity.accepted().body(ResponseHelper.messageBody(
                    ApiMessages.Tasks.EXTERNAL_PACKAGE_DISPATCHED.formatted(taskId)));
        }

        kafkaOutboxService.sendSummarizationTaskEvent(taskId.toString(),
                new SummarizationTaskEvent(taskId.toString(), summarizerTrim, customPrompt, true));
        log.info("Summarization requested for taskId={}, summarizer={}", taskId, summarizerTrim);
        return ResponseEntity.accepted().body(ResponseHelper.messageBody(ApiMessages.Tasks.SUMMARIZATION_REQUESTED));
    }

    @GetMapping("/history/{taskId}/external-llm/package")
    public Map<String, Object> getExternalLlmPackage(@PathVariable UUID taskId) {
        return externalSummarizationCallbackService.buildPackage(taskId);
    }

    @PostMapping("/history/{taskId}/external-llm/dispatch")
    public Map<String, Object> dispatchExternalLlmPackage(@PathVariable UUID taskId) {
        externalSummarizationCallbackService.ensureExternalSummarizationStarted(taskId);
        return externalLlmDispatchService.dispatchPackage(taskId);
    }

    @PostMapping("/history/{taskId}/external-llm/summary")
    public ResponseEntity<Map<String, String>> submitExternalLlmSummary(
            @PathVariable UUID taskId,
            @RequestBody Map<String, Object> body) {
        Object raw = body != null ? body.get(ApiJsonKeys.TEXT) : null;
        String text = raw != null ? String.valueOf(raw) : null;
        externalSummarizationCallbackService.submitExternalSummary(taskId, text);
        return ResponseEntity.ok(Map.of(
                ApiJsonKeys.STATUS, ApiResponseValues.STATUS_SUCCESS,
                ApiJsonKeys.MESSAGE, ApiMessages.Tasks.REPORT_SAVED));
    }

    @GetMapping("/artifacts/{taskId}")
    public ResponseEntity<List<ArtifactInfoDto>> listArtifacts(@PathVariable UUID taskId) {
        List<ArtifactInfoDto> list = artifactRepository.findByTaskIdOrderByFileName(taskId).stream()
                .map(a -> new ArtifactInfoDto(a.getId(), a.getFileName(), a.getOriginalSizeBytes()))
                .toList();
        return ResponseEntity.ok(list);
    }

    @GetMapping("/artifacts/{taskId}/files/{fileName:.+}")
    public ResponseEntity<byte[]> downloadArtifact(
            @PathVariable UUID taskId,
            @PathVariable("fileName") String fileName) {
        Optional<TestArtifactEntity> opt = artifactRepository.findByTaskIdAndFileName(taskId, fileName);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        TestArtifactEntity a = opt.get();
        byte[] content = a.getFileContent();
        if ("gzip".equalsIgnoreCase(a.getContentEncoding())) {
            try {
                content = decompressGzip(content);
            } catch (IOException e) {
                log.warn("Failed to decompress artifact {}", a.getFileName(), e);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            }
        }
        String contentType = contentTypeFromFileName(fileName);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(contentType));
        headers.setContentDispositionFormData("attachment", fileName);
        return ResponseEntity.ok().headers(headers).body(content);
    }

    private enum SummarizerValidationContext {
        RERUN, SUMMARIZE
    }

    private static Map<String, Object> paginatedResponse(List<?> items, int page, int size, Page<?> p) {
        return Map.of(
                ApiJsonKeys.ITEMS, items,
                ApiJsonKeys.PAGE, page,
                ApiJsonKeys.SIZE, size,
                ApiJsonKeys.TOTAL_ELEMENTS, p.getTotalElements(),
                ApiJsonKeys.TOTAL_PAGES, p.getTotalPages());
    }

    private ResponseEntity<Map<String, String>> validateSummarizerRoute(
            SummarizerModelEntity route,
            String summarizerName,
            SummarizerValidationContext context) {
        if (route == null) {
            return ResponseEntity.badRequest().body(ResponseHelper.messageBody(SummarizerRouteMessages.routeNotFound(summarizerName)));
        }
        if (!Boolean.TRUE.equals(route.getEnabled())) {
            String disabledMessage = context == SummarizerValidationContext.RERUN
                    ? SummarizerRouteMessages.ROUTE_DISABLED_RERUN
                    : SummarizerRouteMessages.ROUTE_DISABLED_SUMMARIZE;
            return ResponseEntity.badRequest().body(ResponseHelper.messageBody(disabledMessage));
        }
        if (SummarizerProviders.EXTERNAL.equalsIgnoreCase(route.getProvider())
                && (route.getBaseUrl() == null || route.getBaseUrl().isBlank())) {
            String missingUrlMessage = context == SummarizerValidationContext.RERUN
                    ? SummarizerRouteMessages.EXTERNAL_BASE_URL_MISSING_RERUN
                    : SummarizerRouteMessages.EXTERNAL_BASE_URL_MISSING_SUMMARIZE;
            return ResponseEntity.badRequest().body(ResponseHelper.messageBody(missingUrlMessage));
        }
        return null;
    }

    private TaskQueueItemDto toQueueDto(TestTaskEntity e) {
        return new TaskQueueItemDto(
                e.getId(),
                e.getStatus().name(),
                e.getTestTool(),
                e.getTestFileName(),
                e.getSummarizerName(),
                e.getDockerExecutionProfileId(),
                resolveProfileName(e.getDockerExecutionProfileId()),
                e.getCreatedAt());
    }

    private String resolveProfileName(UUID profileId) {
        if (profileId == null) {
            return null;
        }
        return dockerExecutionProfileRepository.findById(profileId)
                .map(DockerExecutionProfileEntity::getName)
                .orElse(null);
    }

    private TaskHistoryItemDto toHistoryDtoWithContent(TestTaskHistoryEntity e) {
        String fileContent = null;
        if (e.getTestFileContentBase64() != null && !e.getTestFileContentBase64().isEmpty()) {
            try {
                fileContent = new String(Base64.getDecoder().decode(e.getTestFileContentBase64()), StandardCharsets.UTF_8);
            } catch (RuntimeException ex) {
                log.warn("Failed to decode test file content for task {}", e.getId(), ex);
            }
        }
        return new TaskHistoryItemDto(
                e.getId(),
                e.getFinalStatus(),
                e.getTestTool(),
                e.getTestFileName(),
                e.getSummarizerName(),
                e.getCommand(),
                e.getCreatedAt(),
                e.getStartedAt(),
                e.getFinishedAt(),
                e.getErrorMessage(),
                e.getMetricsConfig() != null && !e.getMetricsConfig().isBlank(),
                fileContent,
                e.getMetricsConfig(),
                e.getDockerProfileName());
    }

    private static byte[] decompressGzip(byte[] gzip) throws IOException {
        try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(gzip));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = gis.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private MetricsItemDto toMetricsDto(TestMetricsEntity e) {
        Object metricsData = null;
        if (e.getMetricsData() != null && !e.getMetricsData().isBlank()) {
            try {
                metricsData = objectMapper.readValue(e.getMetricsData(), Object.class);
            } catch (JsonProcessingException ex) {
                metricsData = e.getMetricsData();
            }
        }
        return new MetricsItemDto(
                e.getId(),
                e.getSourceType(),
                e.getEndpointUrl(),
                e.getQueryParams(),
                metricsData,
                e.getCollectedAt());
    }

    private SummaryItemDto toSummaryDto(TestSummaryEntity e) {
        Object summaryData = parseSummaryDataField(e.getSummaryData());
        return new SummaryItemDto(
                e.getId(),
                e.getTaskId(),
                e.getSummaryType(),
                summaryData,
                e.getProcessingStatus(),
                e.getErrorMessage(),
                e.getProcessedAt());
    }

    private Object parseSummaryDataField(String summaryDataJson) {
        if (summaryDataJson == null || summaryDataJson.isBlank()) {
            return null;
        }
        try {
            return unwrapDoubleEncodedJsonString(objectMapper.readValue(summaryDataJson, Object.class));
        } catch (JsonProcessingException ex) {
            return summaryDataJson;
        }
    }

    private Object unwrapDoubleEncodedJsonString(Object parsed) {
        if (!(parsed instanceof String s) || s.isBlank()) {
            return parsed;
        }
        try {
            return objectMapper.readValue(s, Object.class);
        } catch (JsonProcessingException ex) {
            return parsed;
        }
    }

    private static String contentTypeFromFileName(String fileName) {
        if (fileName == null) return "application/octet-stream";
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".html")) return "text/html; charset=utf-8";
        if (lower.endsWith(".csv")) return "text/csv; charset=utf-8";
        if (lower.endsWith(".json")) return "application/json; charset=utf-8";
        if (lower.endsWith(".xml")) return "application/xml; charset=utf-8";
        return "application/octet-stream";
    }
}
