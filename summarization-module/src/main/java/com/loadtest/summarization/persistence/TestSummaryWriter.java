package com.loadtest.summarization.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.loadtest.summarization.util.TestSummaryConstants;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class TestSummaryWriter {

    private final TestSummaryJpaRepository testSummaryJpaRepository;
    private final ObjectMapper objectMapper;

    public void saveSummary(UUID taskId, String summaryType, Map<String, Object> summaryData, String processingStatus, String errorMessage) {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime processedAt = TestSummaryConstants.STATUS_COMPLETED.equals(processingStatus)
                || TestSummaryConstants.STATUS_FAILED.equals(processingStatus) ? now : null;
        String summaryDataJson;
        try {
            summaryDataJson = objectMapper.writeValueAsString(summaryData);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize summary_data: {}", e.getMessage());
            summaryDataJson = "{}";
        }
        TestSummaryEntity entity = TestSummaryEntity.builder()
                .id(UUID.randomUUID())
                .taskId(taskId)
                .summaryType(summaryType)
                .summaryData(summaryDataJson)
                .processingStatus(processingStatus)
                .errorMessage(errorMessage)
                .createdAt(now)
                .processedAt(processedAt)
                .build();
        try {
            testSummaryJpaRepository.save(entity);
            log.info("Saved test_summary: taskId={}, status={}", taskId, processingStatus);
        } catch (RuntimeException e) {
            log.error("Failed to insert test_summary for taskId={}: {}", taskId, e.getMessage());
            throw new TestSummarySaveException("Failed to save summary", e);
        }
    }

    public static class TestSummarySaveException extends RuntimeException {

        public TestSummarySaveException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
