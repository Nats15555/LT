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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExternalSummarizationCallbackService {

    public static final String PROCESSING_STATUS_AWAITING = "AWAITING_EXTERNAL_CALLBACK";

    private final TestTaskHistoryRepository historyRepository;
    private final SummarizerModelRepository summarizerModelRepository;
    private final TestArtifactRepository artifactRepository;
    private final TestMetricsRepository metricsRepository;
    private final TestSummaryRepository summaryRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Value("${loadtest.external-summary.window-minutes:2}")
    private int windowMinutes;

    @Transactional
    public void registerPendingWindow(UUID taskId, String summarizerName) {
        OffsetDateTime deadline = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(windowMinutes);
        Map<String, Object> summaryData = new LinkedHashMap<>();
        summaryData.put("mode", "EXTERNAL_CALLBACK");
        summaryData.put("deadlineAt", deadline.toString());
        summaryData.put("summarizerName", summarizerName);
        summaryData.put("windowMinutes", windowMinutes);
        summaryData.put("instructionsRu",
                "В течение " + windowMinutes + " мин вызовите GET /api/v1/loadtest/history/" + taskId
                        + "/external-llm/package, затем POST /api/v1/loadtest/history/" + taskId
                        + "/external-llm/summary с телом {\"text\":\"...\"}.");

        String summaryDataJson;
        try {
            summaryDataJson = objectMapper.writeValueAsString(summaryData);
        } catch (JsonProcessingException e) {
            summaryDataJson = "{}";
        }

        jdbcTemplate.update("DELETE FROM test_summary WHERE task_id = ?::uuid AND processing_status = ?",
                taskId, PROCESSING_STATUS_AWAITING);

        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbcTemplate.update(
                "INSERT INTO test_summary (id, task_id, summary_type, summary_data, processing_status, error_message, created_at, processed_at) "
                        + "VALUES (?, ?::uuid, ?, ?::jsonb, ?, ?, ?, NULL)",
                id,
                taskId,
                "AI_SUMMARY",
                summaryDataJson,
                PROCESSING_STATUS_AWAITING,
                null,
                now);
        log.info("Opened external summarization window: taskId={}, summarizer={}, deadline={}", taskId, summarizerName, deadline);
    }

    public Map<String, Object> buildPackage(UUID taskId) {
        TestTaskHistoryEntity history = historyRepository.findById(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Прогон не найден"));
        String summarizerName = history.getSummarizerName();
        if (summarizerName == null || summarizerName.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "У прогона не задан summarizer_name");
        }
        SummarizerModelEntity model = summarizerModelRepository.findByName(summarizerName.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Маршрут LLM не найден"));
        if (!"EXTERNAL".equalsIgnoreCase(model.getProvider())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Маршрут не EXTERNAL; используйте Kafka-суммаризацию");
        }

        expireStalePendingRows(taskId);

        TestSummaryEntity pending = summaryRepository
                .findFirstByTaskIdAndProcessingStatusOrderByCreatedAtDesc(taskId, PROCESSING_STATUS_AWAITING)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Нет активного окна внешней суммаризации (ожидайте завершения сбора метрик или запросите суммаризацию повторно)"));

        OffsetDateTime deadline = readDeadline(pending.getSummaryData());
        if (deadline != null && OffsetDateTime.now(ZoneOffset.UTC).isAfter(deadline)) {
            markPendingFailed(pending, "Истекло окно внешней суммаризации");
            throw new ResponseStatusException(HttpStatus.GONE, "Окно callback истекло");
        }

        String reportStructureHintRu =
                "Ожидаемый формат отчёта: секции ## Краткое содержание, ## Плюсы, ## Минусы, ## Предложения, ## Итог (как при вызове встроенного суммаризатора).";
        String summarizationPromptRu = buildSummarizationPromptRu(
                taskId, history, summarizerName.trim(), deadline, pending.getSummaryData(), reportStructureHintRu);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("taskId", taskId.toString());
        root.put("summarizerName", summarizerName.trim());
        root.put("windowMinutes", windowMinutes);
        root.put("deadlineAt", deadline != null ? deadline.toString() : null);
        root.put("summarizationPromptRu", summarizationPromptRu);
        root.put("metrics", buildMetricsPayload(taskId));
        root.put("artifacts", buildArtifactsPayload(taskId));
        root.put("reportStructureHintRu", reportStructureHintRu);
        return root;
    }

    private String buildSummarizationPromptRu(
            UUID taskId,
            TestTaskHistoryEntity history,
            String summarizerName,
            OffsetDateTime deadline,
            String pendingSummaryDataJson,
            String reportStructureHintRu) {
        String instructions = readInstructionsRu(pendingSummaryDataJson);
        StringBuilder sb = new StringBuilder();
        sb.append("Ты помощник по нагрузочному тестированию. Ниже в этом же HTTP-пакете (JSON) есть массивы ")
                .append("«metrics» и «artifacts» — это сырые данные прогона; опирайся на них при написании отчёта.\n\n");
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
        sb.append("## Формат итогового текста\n").append(reportStructureHintRu.trim()).append('\n');
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
            return root.path("instructionsRu").asText("");
        } catch (Exception e) {
            log.warn("Could not read instructionsRu from summary_data: {}", e.getMessage());
            return "";
        }
    }

    @Transactional
    public void submitExternalSummary(UUID taskId, String text) {
        if (text == null || text.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Поле text обязательно");
        }
        TestTaskHistoryEntity history = historyRepository.findById(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Прогон не найден"));
        String summarizerName = history.getSummarizerName();
        if (summarizerName == null || summarizerName.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "У прогона не задан summarizer_name");
        }
        SummarizerModelEntity model = summarizerModelRepository.findByName(summarizerName.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Маршрут LLM не найден"));
        if (!"EXTERNAL".equalsIgnoreCase(model.getProvider())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Маршрут не EXTERNAL");
        }

        expireStalePendingRows(taskId);

        TestSummaryEntity pending = summaryRepository
                .findFirstByTaskIdAndProcessingStatusOrderByCreatedAtDesc(taskId, PROCESSING_STATUS_AWAITING)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Нет активного окна для загрузки отчёта"));

        OffsetDateTime deadline = readDeadline(pending.getSummaryData());
        if (deadline != null && OffsetDateTime.now(ZoneOffset.UTC).isAfter(deadline)) {
            markPendingFailed(pending, "Истекло окно внешней суммаризации");
            throw new ResponseStatusException(HttpStatus.GONE, "Окно callback истекло");
        }

        summaryRepository.deleteByTaskIdAndProcessingStatus(taskId, PROCESSING_STATUS_AWAITING);

        Map<String, Object> summaryData = new LinkedHashMap<>();
        summaryData.put("text", text);
        summaryData.put("model", "external");
        summaryData.put("summarizerName", summarizerName.trim());
        summaryData.put("source", "EXTERNAL_CALLBACK");

        String summaryDataJson;
        try {
            summaryDataJson = objectMapper.writeValueAsString(summaryData);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Не удалось сформировать summary_data");
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        TestSummaryEntity completed = TestSummaryEntity.builder()
                .id(UUID.randomUUID())
                .taskId(taskId)
                .summaryType("AI_SUMMARY")
                .summaryData(summaryDataJson)
                .processingStatus("COMPLETED")
                .errorMessage(null)
                .createdAt(now)
                .processedAt(now)
                .build();
        summaryRepository.save(completed);
        log.info("External summarization report saved: taskId={}", taskId);
    }

    private void expireStalePendingRows(UUID taskId) {
        List<TestSummaryEntity> pendingRows = summaryRepository.findByTaskIdAndProcessingStatusOrderByCreatedAtDesc(
                taskId, PROCESSING_STATUS_AWAITING);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        for (TestSummaryEntity row : pendingRows) {
            OffsetDateTime deadline = readDeadline(row.getSummaryData());
            if (deadline != null && now.isAfter(deadline)) {
                markPendingFailed(row, "Истекло окно внешней суммаризации");
            }
        }
    }

    private void markPendingFailed(TestSummaryEntity row, String message) {
        row.setProcessingStatus("FAILED");
        row.setErrorMessage(message);
        row.setProcessedAt(OffsetDateTime.now(ZoneOffset.UTC));
        summaryRepository.save(row);
    }

    @Transactional
    public void failPendingWindow(UUID taskId, String message) {
        List<TestSummaryEntity> pendingRows = summaryRepository.findByTaskIdAndProcessingStatusOrderByCreatedAtDesc(
                taskId, PROCESSING_STATUS_AWAITING);
        if (pendingRows.isEmpty()) {
            return;
        }
        for (TestSummaryEntity row : pendingRows) {
            markPendingFailed(row, message != null && !message.isBlank() ? message : "FAILED");
        }
    }

    private OffsetDateTime readDeadline(String summaryDataJson) {
        if (summaryDataJson == null || summaryDataJson.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(summaryDataJson);
            JsonNode n = root.get("deadlineAt");
            if (n == null || !n.isTextual()) {
                return null;
            }
            return OffsetDateTime.parse(n.asText());
        } catch (Exception e) {
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
                } catch (Exception ex) {
                    metricsData = e.getMetricsData();
                }
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getId().toString());
            m.put("sourceType", e.getSourceType());
            m.put("endpointUrl", e.getEndpointUrl());
            m.put("queryParams", e.getQueryParams());
            m.put("metricsData", metricsData);
            m.put("collectedAt", e.getCollectedAt() != null ? e.getCollectedAt().toString() : null);
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> buildArtifactsPayload(UUID taskId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (TestArtifactEntity a : artifactRepository.findByTaskIdOrderByFileName(taskId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("fileName", a.getFileName());
            m.put("contentEncoding", a.getContentEncoding());
            m.put("contentBase64", Base64.getEncoder().encodeToString(a.getFileContent()));
            m.put("originalSizeBytes", a.getOriginalSizeBytes());
            out.add(m);
        }
        return out;
    }
}
