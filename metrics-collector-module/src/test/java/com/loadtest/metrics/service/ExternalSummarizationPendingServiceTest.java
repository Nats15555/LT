package com.loadtest.metrics.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

class ExternalSummarizationPendingServiceTest {

    private JdbcTemplate jdbcTemplate;
    private ExternalSummarizationPendingService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        service = new ExternalSummarizationPendingService(jdbcTemplate, new ObjectMapper());
        ReflectionTestUtils.setField(service, "windowMinutes", 2);
    }

    @Test
    void registerAndFailPendingWindow_branches() {
        UUID taskId = UUID.randomUUID();
        service.registerPendingWindow(taskId, "ext");
        verify(jdbcTemplate).update(contains("DELETE FROM test_summary"), eq(taskId),
                eq(ExternalSummarizationPendingService.PROCESSING_STATUS_AWAITING));
        verify(jdbcTemplate).update(contains("INSERT INTO test_summary"), any(), eq(taskId), eq("AI_SUMMARY"),
                any(), eq(ExternalSummarizationPendingService.PROCESSING_STATUS_AWAITING), eq(null), any());

        service.failPendingWindow(taskId, "x");
        verify(jdbcTemplate).update(contains("UPDATE test_summary SET processing_status = 'FAILED'"), eq("x"), any(),
                eq(taskId), eq(ExternalSummarizationPendingService.PROCESSING_STATUS_AWAITING));
        service.failPendingWindow(taskId, " ");
    }

    @Test
    void registerPendingWindow_jsonFailure_fallback() throws Exception {
        ObjectMapper bad = mock(ObjectMapper.class);
        doThrow(new JsonProcessingException("err") {}).when(bad).writeValueAsString(any());
        ExternalSummarizationPendingService s2 = new ExternalSummarizationPendingService(jdbcTemplate, bad);
        ReflectionTestUtils.setField(s2, "windowMinutes", 2);
        s2.registerPendingWindow(UUID.randomUUID(), "ext");
        verify(jdbcTemplate).update(contains("INSERT INTO test_summary"), any(), any(), eq("AI_SUMMARY"),
                eq("{}"), eq(ExternalSummarizationPendingService.PROCESSING_STATUS_AWAITING), eq(null), any());
    }

    @Test
    void failPendingWindow_usesDefaultMessageWhenNull_hitsLine74NullBranch() {
        UUID taskId = UUID.randomUUID();

        service.failPendingWindow(taskId, null);

        verify(jdbcTemplate, times(1)).update(
                contains("UPDATE test_summary SET processing_status = 'FAILED'"),
                eq("FAILED"),
                any(),
                eq(taskId),
                eq(ExternalSummarizationPendingService.PROCESSING_STATUS_AWAITING));
    }
}

