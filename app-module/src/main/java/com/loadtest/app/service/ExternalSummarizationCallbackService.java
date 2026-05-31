package com.loadtest.app.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.loadtest.app.util.ApiJsonKeys;
import com.loadtest.app.util.ApiMessages;
import com.loadtest.app.util.SummarizerProviders;
import com.loadtest.app.util.TestSummaryConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExternalSummarizationCallbackService {

    public static final String PROCESSING_STATUS_AWAITING = "AWAITING_EXTERNAL_CALLBACK";
    private static final String MODE_EXTERNAL_CALLBACK = "EXTERNAL_CALLBACK";

    private final TestTaskHistoryRepository historyRepository;
    private final SummarizerModelRepository summarizerModelRepository;
    private final TestArtifactRepository artifactRepository;
    private final TestMetricsRepository metricsRepository;
    private final TestSummaryRepository summaryRepository;
    private final ObjectMapper objectMapper;
    private final CustomSummarizationPromptStore customSummarizationPromptStore;

    @Value("${loadtest.external-summary.window-minutes:2}")
    private int windowMinutes;

    @Transactional
    public void registerPendingWindow(UUID taskId, String summarizerName) {
        OffsetDateTime deadline = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(windowMinutes);
        String instructionsRu =
                "В течение " + windowMinutes + " мин вызовите GET /api/v1/loadtest/history/" + taskId
                        + "/external-llm/package, затем POST /api/v1/loadtest/history/" + taskId
                        + "/external-llm/summary с телом {\"text\":\"...\"}.";
        Map<String, Object> summaryData = Map.of(
                ApiJsonKeys.MODE, MODE_EXTERNAL_CALLBACK,
                ApiJsonKeys.DEADLINE_AT, deadline.toString(),
                ApiJsonKeys.SUMMARIZER_NAME, summarizerName,
                ApiJsonKeys.WINDOW_MINUTES, windowMinutes,
                ApiJsonKeys.INSTRUCTIONS_RU, instructionsRu);

        String summaryDataJson = serializeSummaryDataOrEmpty(summaryData);

        summaryRepository.deleteByTaskIdAndProcessingStatus(taskId, PROCESSING_STATUS_AWAITING);

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        summaryRepository.save(TestSummaryEntity.builder()
                .id(UUID.randomUUID())
                .taskId(taskId)
                .summaryType(TestSummaryConstants.TYPE_AI_SUMMARY)
                .summaryData(summaryDataJson)
                .processingStatus(PROCESSING_STATUS_AWAITING)
                .errorMessage(null)
                .createdAt(now)
                .processedAt(null)
                .build());
        log.info("Opened external summarization window: taskId={}, summarizer={}, deadline={}", taskId, summarizerName, deadline);
    }

    public Map<String, Object> buildPackage(UUID taskId) {
        return buildPackage(taskId, null);
    }

    public Map<String, Object> buildPackage(UUID taskId, String customPromptOverride) {
        ExternalRunContext ctx = requireExternalRunContext(taskId, true);
        expireStalePendingRows(taskId);

        TestSummaryEntity pending = requireActivePendingWindow(taskId, ApiMessages.ExternalSummarization.NO_ACTIVE_WINDOW);
        ensureCallbackWindowOpen(pending);

        String summarizationPromptRu = resolveSummarizationPromptRu(
                taskId, ctx.history(), ctx.summarizerName(), readDeadline(pending.getSummaryData()),
                pending.getSummaryData(),
                customPromptOverride);

        Map<String, Object> root = new LinkedHashMap<>(Map.of(
                ApiJsonKeys.TASK_ID, taskId.toString(),
                ApiJsonKeys.SUMMARIZER_NAME, ctx.summarizerName(),
                ApiJsonKeys.WINDOW_MINUTES, windowMinutes,
                ApiJsonKeys.SUMMARIZATION_PROMPT_RU, summarizationPromptRu,
                ApiJsonKeys.METRICS, buildMetricsPayload(taskId),
                ApiJsonKeys.ARTIFACTS, buildArtifactsPayload(taskId),
                ApiJsonKeys.REPORT_STRUCTURE_HINT_RU, ApiMessages.ExternalSummarization.REPORT_STRUCTURE_HINT_RU));
        OffsetDateTime deadline = readDeadline(pending.getSummaryData());
        root.put(ApiJsonKeys.DEADLINE_AT, deadline != null ? deadline.toString() : null);
        return root;
    }

    @Transactional
    public void submitExternalSummary(UUID taskId, String text) {
        if (text == null || text.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.ExternalSummarization.TEXT_REQUIRED);
        }
        ExternalRunContext ctx = requireExternalRunContext(taskId, false);
        expireStalePendingRows(taskId);

        TestSummaryEntity pending = requireActivePendingWindow(taskId, ApiMessages.ExternalSummarization.NO_ACTIVE_UPLOAD_WINDOW);
        ensureCallbackWindowOpen(pending);

        summaryRepository.deleteByTaskIdAndProcessingStatus(taskId, PROCESSING_STATUS_AWAITING);

        Map<String, Object> summaryData = Map.of(
                ApiJsonKeys.TEXT, text,
                ApiJsonKeys.MODEL, ApiMessages.ExternalSummarization.EXTERNAL_MODEL_ID,
                ApiJsonKeys.SUMMARIZER_NAME, ctx.summarizerName(),
                ApiJsonKeys.SOURCE, MODE_EXTERNAL_CALLBACK);

        String summaryDataJson;
        try {
            summaryDataJson = objectMapper.writeValueAsString(summaryData);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    ApiMessages.ExternalSummarization.SUMMARY_DATA_SERIALIZATION_FAILED);
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        TestSummaryEntity completed = TestSummaryEntity.builder()
                .id(UUID.randomUUID())
                .taskId(taskId)
                .summaryType(TestSummaryConstants.TYPE_AI_SUMMARY)
                .summaryData(summaryDataJson)
                .processingStatus(TestSummaryConstants.STATUS_COMPLETED)
                .errorMessage(null)
                .createdAt(now)
                .processedAt(now)
                .build();
        summaryRepository.save(completed);
        log.info("External summarization report saved: taskId={}", taskId);
    }

    @Transactional
    public void failPendingWindow(UUID taskId, String message) {
        List<TestSummaryEntity> pendingRows = summaryRepository.findByTaskIdAndProcessingStatusOrderByCreatedAtDesc(
                taskId, PROCESSING_STATUS_AWAITING);
        if (pendingRows.isEmpty()) {
            return;
        }
        for (TestSummaryEntity row : pendingRows) {
            markPendingFailed(row, message != null && !message.isBlank() ? message : TestSummaryConstants.STATUS_FAILED);
        }
    }

    private record ExternalRunContext(TestTaskHistoryEntity history, SummarizerModelEntity model, String summarizerName) {
    }

    private ExternalRunContext requireExternalRunContext(UUID taskId, boolean kafkaSummarizationHint) {
        TestTaskHistoryEntity history = historyRepository.findById(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        ApiMessages.ExternalSummarization.RUN_NOT_FOUND));
        String summarizerName = history.getSummarizerName();
        if (summarizerName == null || summarizerName.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    ApiMessages.ExternalSummarization.SUMMARIZER_NAME_MISSING);
        }
        SummarizerModelEntity model = summarizerModelRepository.findByName(summarizerName.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        ApiMessages.ExternalSummarization.LLM_ROUTE_NOT_FOUND));
        if (!SummarizerProviders.EXTERNAL.equalsIgnoreCase(model.getProvider())) {
            String message = kafkaSummarizationHint
                    ? ApiMessages.ExternalSummarization.ROUTE_NOT_EXTERNAL_USE_KAFKA
                    : ApiMessages.ExternalSummarization.ROUTE_NOT_EXTERNAL;
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
        return new ExternalRunContext(history, model, summarizerName.trim());
    }

    private TestSummaryEntity requireActivePendingWindow(UUID taskId, String notFoundMessage) {
        return summaryRepository
                .findFirstByTaskIdAndProcessingStatusOrderByCreatedAtDesc(taskId, PROCESSING_STATUS_AWAITING)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, notFoundMessage));
    }

    private void ensureCallbackWindowOpen(TestSummaryEntity pending) {
        OffsetDateTime deadline = readDeadline(pending.getSummaryData());
        if (deadline != null && OffsetDateTime.now(ZoneOffset.UTC).isAfter(deadline)) {
            markPendingFailed(pending, ApiMessages.ExternalSummarization.WINDOW_EXPIRED);
            throw new ResponseStatusException(HttpStatus.GONE, ApiMessages.ExternalSummarization.CALLBACK_WINDOW_EXPIRED);
        }
    }

    private String serializeSummaryDataOrEmpty(Map<String, Object> summaryData) {
        try {
            return objectMapper.writeValueAsString(summaryData);
        } catch (JsonProcessingException e) {
            return ApiMessages.ExternalSummarization.EMPTY_SUMMARY_DATA_JSON;
        }
    }

    private String resolveSummarizationPromptRu(
            UUID taskId,
            TestTaskHistoryEntity history,
            String summarizerName,
            OffsetDateTime deadline,
            String pendingSummaryDataJson,
            String customPromptOverride) {
        if (customPromptOverride != null && !customPromptOverride.isBlank()) {
            return customPromptOverride.trim();
        }
        Optional<String> stored = customSummarizationPromptStore.consume(taskId);
        return stored.orElseGet(() -> buildSummarizationPromptRu(
                taskId, history, summarizerName, deadline, pendingSummaryDataJson));
    }

    private String buildSummarizationPromptRu(
            UUID taskId,
            TestTaskHistoryEntity history,
            String summarizerName,
            OffsetDateTime deadline,
            String pendingSummaryDataJson) {
        String instructions = readInstructionsRu(pendingSummaryDataJson);
        StringBuilder sb = new StringBuilder();
        sb.append("Ты помощник по нагрузочному тестированию. Ниже в этом же HTTP-пакете (JSON) есть массивы ")
                .append('«').append(ApiJsonKeys.METRICS).append("» и «").append(ApiJsonKeys.ARTIFACTS)
                .append("» — это сырые данные прогона; опирайся на них при написании отчёта.\n\n");
        sb.append("## Контекст прогона\n");
        sb.append("- taskId: ").append(taskId).append('\n');
        sb.append("- маршрут LLM (summarizer): ").append(summarizerName).append('\n');
        sb.append("- инструмент: ").append(nullToDash(history.getTestTool())).append('\n');
        sb.append("- сценарий (файл): ").append(nullToDash(history.getTestFileName())).append('\n');
        sb.append("- финальный статус: ").append(nullToDash(history.getFinalStatus())).append('\n');
        if (history.getStartedAt() != null) {
            sb.append("- startedAt (UTC): ").append(history.getStartedAt()).append('\n');
        }
        if (history.getFinishedAt() != null) {
            sb.append("- finishedAt (UTC): ").append(history.getFinishedAt()).append('\n');
        }
        if (history.getErrorMessage() != null && !history.getErrorMessage().isBlank()) {
            sb.append("- сообщение об ошибке прогона: ").append(history.getErrorMessage().trim()).append('\n');
        }
        if (deadline != null) {
            sb.append("- ответ с текстом суммаризации нужно вернуть в LoadTest до deadlineAt (UTC): ")
                    .append(deadline).append('\n');
        }
        sb.append('\n');
        if (!instructions.isBlank()) {
            sb.append("## Инструкции LoadTest\n").append(instructions.trim()).append("\n\n");
        }
        sb.append("## Формат итогового текста\n").append(ApiMessages.ExternalSummarization.REPORT_STRUCTURE_HINT_RU.trim()).append('\n');
        sb.append("\nСформируй итоговый отчёт на русском в виде одного текста в Markdown с указанными секциями.\n");
        return sb.toString();
    }

    private static String nullToDash(String s) {
        return s == null || s.isBlank() ? "—" : s;
    }

    private String readInstructionsRu(String summaryDataJson) {
        if (summaryDataJson == null || summaryDataJson.isBlank()) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(summaryDataJson);
            return root.path(ApiJsonKeys.INSTRUCTIONS_RU).asText("");
        } catch (JsonProcessingException e) {
            log.warn("Could not read instructionsRu from summary_data: {}", e.getMessage());
            return "";
        }
    }

    private void expireStalePendingRows(UUID taskId) {
        List<TestSummaryEntity> pendingRows = summaryRepository.findByTaskIdAndProcessingStatusOrderByCreatedAtDesc(
                taskId, PROCESSING_STATUS_AWAITING);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        for (TestSummaryEntity row : pendingRows) {
            OffsetDateTime deadline = readDeadline(row.getSummaryData());
            if (deadline != null && now.isAfter(deadline)) {
                markPendingFailed(row, ApiMessages.ExternalSummarization.WINDOW_EXPIRED);
            }
        }
    }

    private void markPendingFailed(TestSummaryEntity row, String message) {
        row.setProcessingStatus(TestSummaryConstants.STATUS_FAILED);
        row.setErrorMessage(message);
        row.setProcessedAt(OffsetDateTime.now(ZoneOffset.UTC));
        summaryRepository.save(row);
    }

    private OffsetDateTime readDeadline(String summaryDataJson) {
        if (summaryDataJson == null || summaryDataJson.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(summaryDataJson);
            if (root == null) {
                return null;
            }
            JsonNode n = root.get(ApiJsonKeys.DEADLINE_AT);
            if (n == null || !n.isTextual()) {
                return null;
            }
            return OffsetDateTime.parse(n.asText());
        } catch (JsonProcessingException | DateTimeException e) {
            log.warn("Could not parse deadline from summary_data: {}", e.getMessage());
            return null;
        }
    }

    private List<Map<String, Object>> buildMetricsPayload(UUID taskId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (TestMetricsEntity e : metricsRepository.findByTaskIdOrderByCollectedAtAsc(taskId)) {
            Object metricsData = null;
            if (e.getMetricsData() != null && !e.getMetricsData().isBlank()) {
                try {
                    metricsData = objectMapper.readValue(e.getMetricsData(), Object.class);
                } catch (JsonProcessingException ex) {
                    metricsData = e.getMetricsData();
                }
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put(ApiJsonKeys.ID, e.getId().toString());
            m.put(ApiJsonKeys.SOURCE_TYPE, e.getSourceType());
            m.put(ApiJsonKeys.ENDPOINT_URL, e.getEndpointUrl());
            m.put(ApiJsonKeys.QUERY_PARAMS, e.getQueryParams());
            m.put(ApiJsonKeys.METRICS_DATA, metricsData);
            m.put(ApiJsonKeys.COLLECTED_AT, e.getCollectedAt() != null ? e.getCollectedAt().toString() : null);
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> buildArtifactsPayload(UUID taskId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (TestArtifactEntity a : artifactRepository.findByTaskIdOrderByFileName(taskId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put(ApiJsonKeys.FILE_NAME, a.getFileName());
            m.put(ApiJsonKeys.CONTENT_ENCODING, a.getContentEncoding());
            m.put(ApiJsonKeys.CONTENT_BASE64, Base64.getEncoder().encodeToString(a.getFileContent()));
            m.put(ApiJsonKeys.ORIGINAL_SIZE_BYTES, a.getOriginalSizeBytes());
            out.add(m);
        }
        return out;
    }
}
