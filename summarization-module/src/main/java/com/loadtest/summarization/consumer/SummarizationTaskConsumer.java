package com.loadtest.summarization.consumer;

import com.loadtest.summarization.dto.SummarizationTaskEvent;
import com.loadtest.summarization.persistence.SummarizerConfig;
import com.loadtest.summarization.persistence.SummarizerModelRepository;
import com.loadtest.summarization.persistence.TaskHistoryRepository;
import com.loadtest.summarization.persistence.TestSummaryWriter;
import com.loadtest.summarization.service.PromptBuilder;
import com.loadtest.summarization.service.OpenAiCompatibleClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class SummarizationTaskConsumer {

    private final TaskHistoryRepository taskHistoryRepository;
    private final SummarizerModelRepository summarizerModelRepository;
    private final PromptBuilder promptBuilder;
    private final OpenAiCompatibleClient llmClient;
    private final TestSummaryWriter testSummaryWriter;

    @KafkaListener(
            topics = "${kafka.topic.summarization-tasks:summarization-tasks}",
            groupId = "${spring.kafka.consumer.group-id:summarization-service-group}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(SummarizationTaskEvent event, Acknowledgment acknowledgment) {
        String taskIdStr = event.getTaskId();
        log.info("Received summarization event: taskId={}", taskIdStr);

        UUID taskId;
        try {
            taskId = UUID.fromString(taskIdStr);
        } catch (Exception e) {
            log.warn("Invalid taskId: {}", taskIdStr);
            acknowledgment.acknowledge();
            return;
        }

        try {
            final String summarizerName;
            if (event.getSummarizerName() != null && !event.getSummarizerName().isBlank()) {
                summarizerName = event.getSummarizerName().trim();
            } else {
                summarizerName = taskHistoryRepository.getSummarizerNameByTaskId(taskId).orElse(null);
            }
            if (summarizerName == null || summarizerName.isBlank()) {
                log.info("No summarizer for taskId={} (не в событии Kafka и не в test_task_history.summarizer_name), skip", taskId);
                acknowledgment.acknowledge();
                return;
            }

            SummarizerConfig config = summarizerModelRepository.findByName(summarizerName)
                    .orElseThrow(() -> new IllegalStateException("Summarizer not found or disabled: " + summarizerName));

            if ("EXTERNAL".equalsIgnoreCase(config.getProvider())) {
                log.info("Summarizer {} is EXTERNAL — сообщение из Kafka игнорируется (суммаризация через ingest+callback app-module; событие в топик не должно слаться metrics-collector для EXTERNAL)", summarizerName);
                acknowledgment.acknowledge();
                return;
            }

            String prompt = promptBuilder.buildPrompt(taskId);
            long startMs = System.currentTimeMillis();
            log.info("Calling LLM (LiteLLM/OpenAI-compatible) for taskId={}, summarizer={}, baseUrl={}, modelId={} (prompt length={})",
                    taskId, summarizerName, config.getBaseUrl(), config.getModelId(), prompt.length());
            String summaryText = llmClient.summarize(config, prompt);
            long elapsedSec = (System.currentTimeMillis() - startMs) / 1000;
            log.info("LLM responded in {}s for taskId={}", elapsedSec, taskId);

            Map<String, Object> summaryData = Map.of(
                    "text", summaryText,
                    "model", config.getModelId(),
                    "summarizerName", config.getName()
            );
            testSummaryWriter.saveSummary(taskId, "AI_SUMMARY", summaryData, "COMPLETED", null);
            log.info("Summarization completed for taskId={}, summarizer={}", taskId, summarizerName);
        } catch (Exception e) {
            if (e instanceof IllegalArgumentException) {
                log.warn("Суммаризация не выполнена для taskId={}: {}", taskId, e.getMessage());
            } else {
                log.error("Summarization failed for taskId={}", taskId, e);
            }
            try {
                testSummaryWriter.saveSummary(taskId, "AI_SUMMARY",
                        Map.of("error", e.getMessage()),
                        "FAILED",
                        e.getMessage());
            } catch (Exception ex) {
                log.warn("Failed to save FAILED summary: {}", ex.getMessage());
            }
        }
        acknowledgment.acknowledge();
    }
}
