package com.loadtest.metrics.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExternalSummarizationPendingService {

    public static final String PROCESSING_STATUS_AWAITING = "AWAITING_EXTERNAL_CALLBACK";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Value("${loadtest.external-summary.window-minutes:2}")
    private int windowMinutes;

    public void registerPendingWindow(UUID taskId, String summarizerName) {
        OffsetDateTime deadline = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(windowMinutes);
        Map<String, Object> summaryData = new LinkedHashMap<>();
        summaryData.put("mode", "EXTERNAL_CALLBACK");
        summaryData.put("deadlineAt", deadline.toString());
        summaryData.put("summarizerName", summarizerName);
        summaryData.put("windowMinutes", windowMinutes);
        summaryData.put("instructionsRu",
                "В течение " + windowMinutes + " мин вызовите GET /api/v1/loadtest/history/" + taskId
                        + "/external-llm/package (метрики и артефакты), затем POST .../external-llm/summary с телом {\"text\":\"...\"} — текст суммаризированного отчёта.");

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
        log.info("Registered external summarization window: taskId={}, summarizer={}, deadline={}",
                taskId, summarizerName, deadline);
    }

    public void failPendingWindow(UUID taskId, String message) {
        String msg = message != null && !message.isBlank() ? message : "FAILED";
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbcTemplate.update(
                "UPDATE test_summary SET processing_status = 'FAILED', error_message = ?, processed_at = ? " +
                        "WHERE task_id = ?::uuid AND processing_status = ?",
                msg, now, taskId, PROCESSING_STATUS_AWAITING);
        log.info("Marked external pending window FAILED: taskId={}, message={}", taskId, msg);
    }
}
