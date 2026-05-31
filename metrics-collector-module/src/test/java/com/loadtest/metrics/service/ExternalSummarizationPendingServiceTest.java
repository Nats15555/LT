package com.loadtest.metrics.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadtest.metrics.persistence.TestSummaryEntity;
import com.loadtest.metrics.persistence.TestSummaryJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExternalSummarizationPendingServiceTest {

    private TestSummaryJpaRepository testSummaryJpaRepository;
    private ExternalSummarizationPendingService service;

    @BeforeEach
    void setUp() {
        testSummaryJpaRepository = mock(TestSummaryJpaRepository.class);
        service = new ExternalSummarizationPendingService(testSummaryJpaRepository, new ObjectMapper());
        ReflectionTestUtils.setField(service, "windowMinutes", 2);
    }

    @Test
    void registerAndFailPendingWindow_branches() {
        UUID taskId = UUID.randomUUID();
        service.registerPendingWindow(taskId, "ext");
        verify(testSummaryJpaRepository).deleteByTaskIdAndProcessingStatus(taskId,
                ExternalSummarizationPendingService.PROCESSING_STATUS_AWAITING);
        verify(testSummaryJpaRepository).save(any(TestSummaryEntity.class));

        when(testSummaryJpaRepository.findByTaskIdAndProcessingStatus(taskId,
                ExternalSummarizationPendingService.PROCESSING_STATUS_AWAITING))
                .thenReturn(List.of(TestSummaryEntity.builder()
                        .id(UUID.randomUUID())
                        .taskId(taskId)
                        .summaryType("AI_SUMMARY")
                        .summaryData("{}")
                        .processingStatus(ExternalSummarizationPendingService.PROCESSING_STATUS_AWAITING)
                        .build()));

        service.failPendingWindow(taskId, "x");
        verify(testSummaryJpaRepository).saveAll(any());
        service.failPendingWindow(taskId, " ");
    }

    @Test
    void registerPendingWindow_jsonFailure_fallback() throws Exception {
        ObjectMapper bad = mock(ObjectMapper.class);
        doThrow(new JsonProcessingException("err") {}).when(bad).writeValueAsString(any());
        ExternalSummarizationPendingService s2 = new ExternalSummarizationPendingService(testSummaryJpaRepository, bad);
        ReflectionTestUtils.setField(s2, "windowMinutes", 2);
        s2.registerPendingWindow(UUID.randomUUID(), "ext");
        verify(testSummaryJpaRepository).save(any(TestSummaryEntity.class));
    }

    @Test
    void failPendingWindow_usesDefaultMessageWhenNull() {
        UUID taskId = UUID.randomUUID();
        when(testSummaryJpaRepository.findByTaskIdAndProcessingStatus(taskId,
                ExternalSummarizationPendingService.PROCESSING_STATUS_AWAITING))
                .thenReturn(List.of());

        service.failPendingWindow(taskId, null);

        verify(testSummaryJpaRepository, times(1)).saveAll(eq(List.of()));
    }
}
