package com.loadtest.summarization.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class TestSummaryWriter {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public void saveSummary(UUID taskId, String summaryType, Map<String, Object> summaryData, String processingStatus, String errorMessage) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime processedAt = "COMPLETED".equals(processingStatus) || "FAILED".equals(processingStatus) ? now : null;
        String summaryDataJson;
        try {
            summaryDataJson = objectMapper.writeValueAsString(summaryData);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize summary_data: {}", e.getMessage());
            summaryDataJson = "{}";
        }
        try {
            jdbcTemplate.update(
                    "INSERT INTO test_summary (id, task_id, summary_type, summary_data, processing_status, error_message, created_at, processed_at) VALUES (?, ?::uuid, ?, ?::jsonb, ?, ?, ?, ?)",
                    id, taskId, summaryType, summaryDataJson, processingStatus, errorMessage, now, processedAt);
            log.info("Saved test_summary: taskId={}, status={}", taskId, processingStatus);
        } catch (Exception e) {
            log.error("Failed to insert test_summary for taskId={}: {}", taskId, e.getMessage());
            throw new RuntimeException("Failed to save summary", e);
        }
    }
}
