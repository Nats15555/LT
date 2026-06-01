package com.loadtest.metrics.service;

import com.loadtest.metrics.dto.MetricsCollectionResponse;
import com.loadtest.metrics.persistence.SummarizerProviderRepository;
import com.loadtest.metrics.persistence.TaskMetricsConfigRepository;
import com.loadtest.metrics.util.TestSummaryConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PostMetricsPipelineService {

    private final TaskHistoryLifecycleService taskHistoryLifecycleService;
    private final TaskMetricsConfigRepository taskMetricsConfigRepository;
    private final SummarizerProviderRepository summarizerProviderRepository;
    private final SummarizationEnqueueService summarizationEnqueueService;

    @Value("${loadtest.summarization.default-summarizer-name:}")
    private String defaultSummarizerName;

    public void finishMetricsPhase(String taskIdStr, MetricsCollectionResponse response, boolean collectionAttempted) {
        UUID taskId;
        try {
            taskId = UUID.fromString(taskIdStr);
        } catch (RuntimeException e) {
            log.warn("Invalid taskId for post-metrics pipeline: {}", taskIdStr);
            return;
        }

        if (response != null && TestSummaryConstants.STATUS_FAILED.equals(response.status())) {
            String msg = response.message() != null && !response.message().isBlank()
                    ? response.message()
                    : "Metrics collection failed";
            taskHistoryLifecycleService.markFailed(taskId, msg);
            return;
        }

        Optional<String> summarizer = resolveSummarizerName(taskId);
        if (summarizer.isEmpty()) {
            taskHistoryLifecycleService.markCompleted(taskId);
            log.info("Post-metrics: taskId={} completed (no summarizer, collectionAttempted={})",
                    taskIdStr, collectionAttempted);
            return;
        }

        String name = summarizer.get();
        if (!summarizerProviderRepository.isSummarizerEnabled(name)) {
            taskHistoryLifecycleService.markCompleted(taskId);
            log.info("Post-metrics: taskId={} completed (summarizer {} disabled)", taskIdStr, name);
            return;
        }

        try {
            summarizationEnqueueService.enqueueSummarizationForTask(taskIdStr, name);
        } catch (SummarizationEnqueueException e) {
            taskHistoryLifecycleService.markFailed(taskId, e.getMessage());
        }
    }

    public void failMetricsPhase(String taskIdStr, String message) {
        UUID taskId;
        try {
            taskId = UUID.fromString(taskIdStr);
        } catch (RuntimeException e) {
            log.warn("Invalid taskId for metrics failure: {}", taskIdStr);
            return;
        }
        taskHistoryLifecycleService.markFailed(taskId,
                message != null && !message.isBlank() ? message : "Metrics collection failed");
    }

    private Optional<String> resolveSummarizerName(UUID taskId) {
        Optional<String> fromDb = taskMetricsConfigRepository.findSummarizerNameByTaskId(taskId)
                .filter(s -> !s.isBlank());
        if (fromDb.isPresent()) {
            return fromDb;
        }
        if (defaultSummarizerName != null && !defaultSummarizerName.isBlank()) {
            return Optional.of(defaultSummarizerName.trim());
        }
        return Optional.empty();
    }
}
