package com.loadtest.metrics.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadtest.metrics.persistence.TestSummaryEntity;
import com.loadtest.metrics.persistence.TestSummaryJpaRepository;
import com.loadtest.metrics.util.TestSummaryConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExternalSummarizationPendingService {

    public static final String PROCESSING_STATUS_AWAITING = "AWAITING_EXTERNAL_CALLBACK";

    private final TestSummaryJpaRepository testSummaryJpaRepository;
    private final TaskHistoryLifecycleService taskHistoryLifecycleService;
    private final ObjectMapper objectMapper;

    @Value("${loadtest.external-summary.window-minutes:2}")
    private int windowMinutes;

    @Transactional
    public void registerPendingWindow(UUID taskId, String summarizerName) {
        OffsetDateTime deadline = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(windowMinutes);
        String instructionsRu =
                "В течение " + windowMinutes + " мин вызовите GET /api/v1/loadtest/history/" + taskId
                        + "/external-llm/package (метрики и артефакты), затем POST .../external-llm/summary с телом {\"text\":\"...\"} — текст суммаризированного отчёта.";
        Map<String, Object> summaryData = Map.of(
                "mode", "EXTERNAL_CALLBACK",
                "deadlineAt", deadline.toString(),
                "summarizerName", summarizerName,
                "windowMinutes", windowMinutes,
                "instructionsRu", instructionsRu);

        String summaryDataJson;
        try {
            summaryDataJson = objectMapper.writeValueAsString(summaryData);
        } catch (JsonProcessingException e) {
            summaryDataJson = "{}";
        }

        testSummaryJpaRepository.deleteByTaskIdAndProcessingStatus(taskId, PROCESSING_STATUS_AWAITING);

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        testSummaryJpaRepository.save(TestSummaryEntity.builder()
                .id(UUID.randomUUID())
                .taskId(taskId)
                .summaryType(TestSummaryConstants.TYPE_AI_SUMMARY)
                .summaryData(summaryDataJson)
                .processingStatus(PROCESSING_STATUS_AWAITING)
                .errorMessage(null)
                .createdAt(now)
                .processedAt(null)
                .build());
        log.info("Registered external summarization window: taskId={}, summarizer={}, deadline={}",
                taskId, summarizerName, deadline);
    }

    @Transactional
    public void failPendingWindow(UUID taskId, String message) {
        String msg = message != null && !message.isBlank() ? message : TestSummaryConstants.STATUS_FAILED;
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        List<TestSummaryEntity> rows = testSummaryJpaRepository.findByTaskIdAndProcessingStatus(
                taskId, PROCESSING_STATUS_AWAITING);
        for (TestSummaryEntity row : rows) {
            row.setProcessingStatus(TestSummaryConstants.STATUS_FAILED);
            row.setErrorMessage(msg);
            row.setProcessedAt(now);
        }
        testSummaryJpaRepository.saveAll(rows);
        taskHistoryLifecycleService.markFailed(taskId, msg);
        log.info("Marked external pending window FAILED: taskId={}, message={}", taskId, msg);
    }
}
