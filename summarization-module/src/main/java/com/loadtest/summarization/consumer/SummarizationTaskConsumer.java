package com.loadtest.summarization.consumer;

import com.loadtest.summarization.dto.SummarizationTaskEvent;
import com.loadtest.summarization.persistence.SummarizerConfig;
import com.loadtest.summarization.persistence.SummarizerModelRepository;
import com.loadtest.summarization.persistence.TaskHistoryRepository;
import com.loadtest.summarization.persistence.TestSummaryWriter;
import com.loadtest.summarization.service.OpenAiCompatibleClient;
import com.loadtest.summarization.service.PromptBuilder;
import com.loadtest.summarization.service.TaskHistoryLifecycleService;
import com.loadtest.summarization.util.DatabaseAvailabilityService;
import com.loadtest.summarization.util.DatabaseUnavailableException;
import com.loadtest.summarization.util.TestSummaryConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class SummarizationTaskConsumer {

    private final DatabaseAvailabilityService databaseAvailabilityService;
    private final TaskHistoryRepository taskHistoryRepository;
    private final SummarizerModelRepository summarizerModelRepository;
    private final PromptBuilder promptBuilder;
    private final OpenAiCompatibleClient llmClient;
    private final TestSummaryWriter testSummaryWriter;
    private final TaskHistoryLifecycleService taskHistoryLifecycleService;

    @KafkaListener(
            topics = "${kafka.topic.summarization-tasks:summarization-tasks}",
            groupId = "${spring.kafka.consumer.group-id:summarization-service-group}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(SummarizationTaskEvent event, Acknowledgment acknowledgment) {
        log.info("Received summarization event: taskId={}", event.taskId());
        Optional<UUID> taskIdOpt = parseTaskId(event.taskId());
        if (taskIdOpt.isEmpty()) {
            acknowledgment.acknowledge();
            return;
        }
        UUID taskId = taskIdOpt.get();
        try {
            databaseAvailabilityService.requireAvailable();
            if (!event.isForceRetry() && taskHistoryRepository.hasTerminalStatus(taskId)) {
                log.info("Skip duplicate summarization for taskId={}: history already COMPLETED or FAILED",
                        taskId);
                acknowledgment.acknowledge();
                return;
            }
            executeSummarization(event, taskId);
            acknowledgment.acknowledge();
        } catch (DatabaseUnavailableException e) {
            log.warn("PostgreSQL unavailable for taskId={}, message will be redelivered: {}",
                    taskId, e.getMessage());
        } catch (RuntimeException e) {
            if (DatabaseAvailabilityService.isDatabaseAccessFailure(e)) {
                log.warn("Database error for taskId={}, message will be redelivered: {}", taskId, e.getMessage());
                return;
            }
            throw e;
        }
    }

    private Optional<UUID> parseTaskId(String taskIdStr) {
        try {
            return Optional.of(UUID.fromString(taskIdStr));
        } catch (RuntimeException e) {
            log.warn("Invalid taskId: {}", taskIdStr);
            return Optional.empty();
        }
    }

    private void executeSummarization(SummarizationTaskEvent event, UUID taskId) {
        String summarizerName = resolveSummarizerName(event, taskId);
        if (summarizerName == null) {
            log.info("No summarizer for taskId={} (не в событии Kafka и не в test_task_history.summarizer_name), skip",
                    taskId);
            return;
        }

        SummarizerConfig config = summarizerModelRepository.findByName(summarizerName).orElse(null);
        if (config == null) {
            IllegalStateException e = new IllegalStateException(
                    "Summarizer not found or disabled: " + summarizerName);
            saveFailedSummary(taskId, null, e);
            taskHistoryLifecycleService.markFailed(taskId, e.getMessage());
            return;
        }

        if ("EXTERNAL".equalsIgnoreCase(config.getProvider())) {
            log.info("Summarizer {} is EXTERNAL — сообщение из Kafka игнорируется "
                            + "(суммаризация через ingest+callback app-module; событие в топик не должно слаться "
                            + "metrics-collector для EXTERNAL)",
                    summarizerName);
            return;
        }

        taskHistoryLifecycleService.markAnalyzing(taskId);
        String prompt = resolvePrompt(event, taskId);
        runLlmSummarization(taskId, summarizerName, config, prompt);
    }

    private void runLlmSummarization(
            UUID taskId, String summarizerName, SummarizerConfig config, String prompt) {
        try {
            callLlmAndPersistSuccess(taskId, summarizerName, config, prompt);
        } catch (RuntimeException e) {
            handleSummarizationFailure(taskId, prompt, e);
        }
    }

    private String resolveSummarizerName(SummarizationTaskEvent event, UUID taskId) {
        if (event.summarizerName() != null && !event.summarizerName().isBlank()) {
            return event.summarizerName().trim();
        }
        return taskHistoryRepository.getSummarizerNameByTaskId(taskId)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .orElse(null);
    }

    private String resolvePrompt(SummarizationTaskEvent event, UUID taskId) {
        if (event.customPrompt() != null && !event.customPrompt().isBlank()) {
            String prompt = event.customPrompt().trim();
            log.info("Using user-provided custom prompt for taskId={} (length={})", taskId, prompt.length());
            return prompt;
        }
        return promptBuilder.buildPrompt(taskId);
    }

    private void callLlmAndPersistSuccess(UUID taskId, String summarizerName, SummarizerConfig config, String prompt) {
        long startMs = System.currentTimeMillis();
        log.info("Calling LLM (LiteLLM/OpenAI-compatible) for taskId={}, summarizer={}, baseUrl={}, modelId={} "
                        + "(prompt length={})",
                taskId, summarizerName, config.getBaseUrl(), config.getModelId(), prompt.length());
        String summaryText = llmClient.summarize(config, prompt);
        long elapsedSec = (System.currentTimeMillis() - startMs) / 1000;
        log.info("LLM responded in {}s for taskId={}", elapsedSec, taskId);

        Map<String, Object> summaryData = Map.of(
                "text", summaryText,
                "model", config.getModelId(),
                "summarizerName", config.getName(),
                "promptUsed", prompt);
        testSummaryWriter.saveSummary(taskId, TestSummaryConstants.TYPE_AI_SUMMARY, summaryData,
                TestSummaryConstants.STATUS_COMPLETED, null);
        taskHistoryLifecycleService.markCompleted(taskId);
        log.info("Summarization completed for taskId={}, summarizer={}", taskId, summarizerName);
    }

    private void handleSummarizationFailure(UUID taskId, String promptUsed, RuntimeException e) {
        if (DatabaseAvailabilityService.isDatabaseAccessFailure(e)) {
            throw e;
        }
        if (e instanceof IllegalArgumentException) {
            log.warn("Суммаризация не выполнена для taskId={}: {}", taskId, e.getMessage());
        } else {
            log.error("Summarization failed for taskId={}", taskId, e);
        }
        saveFailedSummary(taskId, promptUsed, e);
        taskHistoryLifecycleService.markFailed(taskId,
                e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
    }

    private void saveFailedSummary(UUID taskId, String promptUsed, RuntimeException e) {
        String error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        Map<String, Object> failedSummaryData = promptUsed != null && !promptUsed.isBlank()
                ? Map.of("error", error, "promptUsed", promptUsed)
                : Map.of("error", error);
        testSummaryWriter.saveSummary(taskId, TestSummaryConstants.TYPE_AI_SUMMARY, failedSummaryData,
                TestSummaryConstants.STATUS_FAILED, e.getMessage());
    }
}
