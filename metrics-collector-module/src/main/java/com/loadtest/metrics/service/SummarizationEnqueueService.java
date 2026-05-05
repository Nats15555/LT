package com.loadtest.metrics.service;

import com.loadtest.metrics.dto.SummarizationTaskEvent;
import com.loadtest.metrics.persistence.SummarizerProviderRepository;
import com.loadtest.metrics.persistence.TaskMetricsConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SummarizationEnqueueService {

    private final KafkaOutboxService kafkaOutboxService;
    private final TaskMetricsConfigRepository taskMetricsConfigRepository;
    private final SummarizerProviderRepository summarizerProviderRepository;
    private final ExternalSummarizationPendingService externalSummarizationPendingService;

    private final WebClient webClient = WebClient.builder()
            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
            .build();

    @Value("${loadtest.app.base-url:http://localhost:8080}")
    private String appBaseUrl;

    @Value("${kafka.topic.summarization-tasks:summarization-tasks}")
    private String summarizationTasksTopic;

    @Value("${loadtest.summarization.default-summarizer-name:}")
    private String defaultSummarizerName;

    public void enqueueAfterMetricsSaved(String taskIdStr) {
        UUID taskId;
        try {
            taskId = UUID.fromString(taskIdStr);
        } catch (Exception e) {
            log.warn("Invalid taskId for summarization enqueue: {}", taskIdStr);
            return;
        }
        Optional<String> fromDb = taskMetricsConfigRepository.findSummarizerNameByTaskId(taskId)
                .filter(s -> !s.isBlank());
        String summarizer = fromDb.orElseGet(() ->
                defaultSummarizerName != null && !defaultSummarizerName.isBlank()
                        ? defaultSummarizerName.trim()
                        : null);
        if (summarizer == null || summarizer.isBlank()) {
            log.info("После метрик не отправлено в summarization-tasks: taskId={} — у прогона не задан summarizer_name (выберите суммаризатор в /upload или передайте его при ручном перезапросе)",
                    taskIdStr);
            return;
        }
        if (!summarizerProviderRepository.isSummarizerEnabled(summarizer)) {
            log.info("После метрик суммаризация пропущена: маршрут «{}» выключен (enabled=false) в summarizer_models, taskId={}",
                    summarizer, taskIdStr);
            return;
        }
        if (summarizerProviderRepository.findProviderBySummarizerName(summarizer)
                .map(p -> "EXTERNAL".equalsIgnoreCase(p))
                .orElse(false)) {
            try {
                externalSummarizationPendingService.registerPendingWindow(taskId, summarizer);
                log.info("External summarizer: registered callback window instead of Kafka: taskId={}, summarizer={}",
                        taskIdStr, summarizer);
                boolean dispatched = triggerExternalDispatch(taskId);
                if (!dispatched) {
                    externalSummarizationPendingService.failPendingWindow(taskId,
                            "Не удалось отправить пакет во внешний контур (dispatch не выполнен)");
                }
            } catch (Exception e) {
                log.warn("Failed to register external summarization window for task {}", taskIdStr, e);
            }
            return;
        }
        try {
            kafkaOutboxService.sendSummarizationEvent(taskIdStr, new SummarizationTaskEvent(taskIdStr, summarizer));
            log.info("Enqueued summarization after metrics: taskId={}, summarizer={} (fromDb={})",
                    taskIdStr, summarizer, fromDb.isPresent());
        } catch (Exception e) {
            log.warn("Failed to enqueue summarization for task {}", taskIdStr, e);
        }
    }

    private boolean triggerExternalDispatch(UUID taskId) {
        String base = appBaseUrl != null ? appBaseUrl.replaceAll("/$", "") : "";
        if (base.isBlank()) {
            log.warn("loadtest.app.base-url is blank; cannot trigger external dispatch");
            return false;
        }
        String url = base + "/api/v1/loadtest/history/" + taskId + "/external-llm/dispatch";
        try {
            String body = webClient.post()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            log.info("Triggered external dispatch: taskId={}, response={}", taskId, body != null ? body : "(empty)");
            return true;
        } catch (Exception e) {
            log.warn("Failed to trigger external dispatch for taskId={}: {}", taskId, e.getMessage());
            return false;
        }
    }
}
