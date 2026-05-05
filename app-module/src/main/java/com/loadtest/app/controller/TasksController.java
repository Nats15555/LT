package com.loadtest.app.controller;

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
import com.loadtest.app.service.ExternalLlmDispatchService;
import com.loadtest.app.service.ExternalSummarizationCallbackService;
import com.loadtest.app.service.KafkaOutboxService;
import com.loadtest.app.service.QueuePauseService;
import com.loadtest.app.service.TestQueueService;
import com.loadtest.app.util.ResponseHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
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
    private final KafkaOutboxService kafkaOutboxService;
    private final TestQueueService testQueueService;
    private final QueuePauseService queuePauseService;
    private final ObjectMapper objectMapper;
    @Value("${kafka.topic.summarization-tasks:summarization-tasks}")
    private String summarizationTasksTopic;

    @GetMapping("/queue/pause")
    public Map<String, Object> getQueuePause() {
        var s = queuePauseService.getState();
        return Map.of(
                "paused", s.paused(),
                "pendingKafkaDispatchCount", s.pendingKafkaDispatchCount());
    }

    @PutMapping("/queue/pause")
    public ResponseEntity<Map<String, Object>> setQueuePause(@RequestBody Map<String, Object> body) {
        if (body == null || !body.containsKey("paused")) {
            return ResponseEntity.badRequest().body(Map.of("message", "Тело JSON должно содержать поле paused (boolean)."));
        }
        Object raw = body.get("paused");
        boolean paused;
        if (raw instanceof Boolean b) {
            paused = b;
        } else {
            paused = Boolean.parseBoolean(String.valueOf(raw));
        }
        var s = queuePauseService.setPaused(paused);
        return ResponseEntity.ok(Map.of(
                "paused", s.paused(),
                "pendingKafkaDispatchCount", s.pendingKafkaDispatchCount()));
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
                .collect(Collectors.toList());

        return Map.of(
                "items", items,
                "page", safePage,
                "size", safeSize,
                "totalElements", p.getTotalElements(),
                "totalPages", p.getTotalPages());
    }

    @DeleteMapping("/tasks/{taskId}")
    public ResponseEntity<Map<String, String>> deleteQueuedTask(@PathVariable UUID taskId) {
        return switch (testQueueService.deletePendingQueueTask(taskId)) {
            case DELETED -> ResponseEntity.ok(Map.of(
                    "status", "success",
                    "message", "Задача удалена из очереди"));
            case NOT_FOUND -> ResponseEntity.notFound().build();
            case NOT_DELETABLE -> ResponseHelper.buildErrorResponse(HttpStatus.CONFLICT,
                    "Нельзя удалить задачу: она уже выполняется (PROCESSING) или не в статусе ожидания.");
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
                .collect(Collectors.toList());

        return Map.of(
                "items", items,
                "page", safePage,
                "size", safeSize,
                "totalElements", p.getTotalElements(),
                "totalPages", p.getTotalPages());
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
        if (body != null && body.get("summarizer") != null) {
            String s = String.valueOf(body.get("summarizer")).trim();
            if (!s.isEmpty()) {
                summarizerOverride = s;
            }
        }
        if (summarizerOverride != null) {
            var route = summarizerModelRepository.findByName(summarizerOverride).orElse(null);
            if (route == null) {
                return ResponseEntity.badRequest().body(Map.of(
                        "message", "Маршрут LLM «" + summarizerOverride + "» не найден."));
            }
            if (!Boolean.TRUE.equals(route.getEnabled())) {
                return ResponseEntity.badRequest().body(Map.of(
                        "message", "Маршрут LLM выключен (enabled=false). Включите его или выберите другой."));
            }
            if ("EXTERNAL".equalsIgnoreCase(route.getProvider())
                    && (route.getBaseUrl() == null || route.getBaseUrl().isBlank())) {
                return ResponseEntity.badRequest().body(Map.of(
                        "message", "У маршрута EXTERNAL не задан URL приёма пакета (baseUrl)."));
            }
        }
        try {
            String newTaskId = testQueueService.rerunFromHistory(taskId, summarizerOverride);
            return ResponseEntity.ok(Map.of("status", "success", "message", "Тест поставлен в очередь", "taskId", newTaskId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/metrics/{taskId}")
    public ResponseEntity<List<MetricsItemDto>> listMetrics(@PathVariable UUID taskId) {
        List<MetricsItemDto> list = metricsRepository.findByTaskIdOrderByCollectedAtAsc(taskId).stream()
                .map(this::toMetricsDto)
                .collect(Collectors.toList());
        return ResponseEntity.ok(list);
    }

    @GetMapping("/history/{taskId}/summary")
    public ResponseEntity<List<SummaryItemDto>> listSummary(@PathVariable UUID taskId) {
        List<SummaryItemDto> list = summaryRepository.findByTaskIdOrderByProcessedAtDesc(taskId).stream()
                .map(this::toSummaryDto)
                .collect(Collectors.toList());
        return ResponseEntity.ok(list);
    }

    @PostMapping("/history/{taskId}/summarize")
    public ResponseEntity<Map<String, String>> requestSummarization(
            @PathVariable UUID taskId,
            @RequestBody(required = false) Map<String, String> body) {
        TestTaskHistoryEntity history = historyRepository.findById(taskId).orElse(null);
        if (history == null) {
            return ResponseEntity.notFound().build();
        }
        String summarizer = body != null && body.containsKey("summarizer") ? body.get("summarizer") : history.getSummarizerName();
        if (summarizer == null || summarizer.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Укажите маршрут LLM (summarizer) или запустите тест с выбранным маршрутом в форме /upload"));
        }
        String summarizerTrim = summarizer.trim();
        history.setSummarizerName(summarizerTrim);
        historyRepository.save(history);

        SummarizerModelEntity route = summarizerModelRepository.findByName(summarizerTrim)
                .orElse(null);
        if (route == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "Маршрут LLM «" + summarizerTrim + "» не найден."));
        }
        if (!Boolean.TRUE.equals(route.getEnabled())) {
            return ResponseEntity.badRequest().body(Map.of("message", "Маршрут LLM выключен (enabled=false). Включите его в конфигурации или выберите другой."));
        }

        if ("EXTERNAL".equalsIgnoreCase(route.getProvider())) {
            String ingest = route.getBaseUrl();
            if (ingest == null || ingest.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "message",
                        "У маршрута EXTERNAL не задан полный URL приёма пакета (baseUrl в записи summarizer_models)."));
            }
            externalSummarizationCallbackService.registerPendingWindow(taskId, summarizerTrim);
            try {
                externalLlmDispatchService.dispatchPackage(taskId);
            } catch (ResponseStatusException e) {
                String msg = e.getReason();
                if (msg == null || msg.isBlank()) {
                    msg = "Не удалось отправить пакет во внешний контур (ingest). Проверьте base_url маршрута и доступность mock.";
                }
                log.warn("External dispatch after UI summarize failed: taskId={}, httpStatus={}, message={}",
                        taskId, e.getStatusCode().value(), msg);
                return ResponseEntity.status(e.getStatusCode().value()).body(Map.of("message", msg));
            }
            log.info("External summarization: window opened and package dispatched from UI: taskId={}, summarizer={}",
                    taskId, summarizerTrim);
            return ResponseEntity.accepted().body(Map.of(
                    "message",
                    "Пакет метрик и артефактов отправлен на ingest внешнего контура. После приёма (received=true) mock должен вызвать POST …/external-llm/summary. "
                            + "Ручной сценарий: GET /api/v1/loadtest/history/" + taskId + "/external-llm/package."));
        }

        kafkaOutboxService.sendSummarizationTaskEvent(taskId.toString(),
                new SummarizationTaskEvent(taskId.toString(), summarizerTrim));
        log.info("Summarization requested for taskId={}, summarizer={}", taskId, summarizerTrim);
        return ResponseEntity.accepted().body(Map.of("message", "Суммаризация запрошена. Обновите страницу через несколько секунд."));
    }

    @GetMapping("/history/{taskId}/external-llm/package")
    public Map<String, Object> getExternalLlmPackage(@PathVariable UUID taskId) {
        return externalSummarizationCallbackService.buildPackage(taskId);
    }

    @PostMapping("/history/{taskId}/external-llm/dispatch")
    public Map<String, Object> dispatchExternalLlmPackage(@PathVariable UUID taskId) {
        return externalLlmDispatchService.dispatchPackage(taskId);
    }

    @PostMapping("/history/{taskId}/external-llm/summary")
    public ResponseEntity<Map<String, String>> submitExternalLlmSummary(
            @PathVariable UUID taskId,
            @RequestBody Map<String, Object> body) {
        Object raw = body != null ? body.get("text") : null;
        String text = raw != null ? String.valueOf(raw) : null;
        externalSummarizationCallbackService.submitExternalSummary(taskId, text);
        return ResponseEntity.ok(Map.of("status", "success", "message", "Отчёт сохранён"));
    }

    @GetMapping("/artifacts/{taskId}")
    public ResponseEntity<List<ArtifactInfoDto>> listArtifacts(@PathVariable UUID taskId) {
        List<ArtifactInfoDto> list = artifactRepository.findByTaskIdOrderByFileName(taskId).stream()
                .map(a -> ArtifactInfoDto.builder()
                        .id(a.getId())
                        .fileName(a.getFileName())
                        .originalSizeBytes(a.getOriginalSizeBytes())
                        .build())
                .collect(Collectors.toList());
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
            } catch (Exception e) {
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

    private TaskQueueItemDto toQueueDto(TestTaskEntity e) {
        return TaskQueueItemDto.builder()
                .taskId(e.getId())
                .status(e.getStatus().name())
                .testTool(e.getTestTool())
                .testFileName(e.getTestFileName())
                .summarizerName(e.getSummarizerName())
                .dockerExecutionProfileId(e.getDockerExecutionProfileId())
                .dockerProfileName(resolveProfileName(e.getDockerExecutionProfileId()))
                .createdAt(e.getCreatedAt())
                .build();
    }

    private String resolveProfileName(java.util.UUID profileId) {
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
            } catch (Exception ex) {
                log.warn("Failed to decode test file content for task {}", e.getId(), ex);
            }
        }
        return TaskHistoryItemDto.builder()
                .id(e.getId())
                .finalStatus(e.getFinalStatus())
                .testTool(e.getTestTool())
                .testFileName(e.getTestFileName())
                .summarizerName(e.getSummarizerName())
                .command(e.getCommand())
                .createdAt(e.getCreatedAt())
                .startedAt(e.getStartedAt())
                .finishedAt(e.getFinishedAt())
                .errorMessage(e.getErrorMessage())
                .fileContent(fileContent)
                .metricsConfig(e.getMetricsConfig())
                .metricsCollected(e.getMetricsConfig() != null && !e.getMetricsConfig().isBlank())
                .dockerProfileName(e.getDockerProfileName())
                .build();
    }

    private static byte[] decompressGzip(byte[] gzip) throws Exception {
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
            } catch (Exception ex) {
                metricsData = e.getMetricsData();
            }
        }
        return MetricsItemDto.builder()
                .id(e.getId())
                .sourceType(e.getSourceType())
                .endpointUrl(e.getEndpointUrl())
                .queryParams(e.getQueryParams())
                .metricsData(metricsData)
                .collectedAt(e.getCollectedAt())
                .build();
    }

    private SummaryItemDto toSummaryDto(TestSummaryEntity e) {
        Object summaryData = null;
        if (e.getSummaryData() != null && !e.getSummaryData().isBlank()) {
            try {
                summaryData = objectMapper.readValue(e.getSummaryData(), Object.class);
                if (summaryData instanceof String s && !s.isBlank()) {
                    try {
                        summaryData = objectMapper.readValue(s, Object.class);
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ex) {
                summaryData = e.getSummaryData();
            }
        }
        return SummaryItemDto.builder()
                .id(e.getId())
                .taskId(e.getTaskId())
                .summaryType(e.getSummaryType())
                .summaryData(summaryData)
                .processingStatus(e.getProcessingStatus())
                .errorMessage(e.getErrorMessage())
                .processedAt(e.getProcessedAt())
                .build();
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
