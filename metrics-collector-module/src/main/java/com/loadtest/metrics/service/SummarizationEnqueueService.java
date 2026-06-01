package com.loadtest.metrics.service;

import com.loadtest.metrics.dto.SummarizationTaskEvent;
import com.loadtest.metrics.persistence.SummarizerProviderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SummarizationEnqueueService {

    private final KafkaOutboxService kafkaOutboxService;
    private final SummarizerProviderRepository summarizerProviderRepository;
    private final ExternalSummarizationPendingService externalSummarizationPendingService;

    private final WebClient webClient = WebClient.builder()
            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
            .build();

    @Value("${loadtest.app.base-url:http://localhost:8080}")
    private String appBaseUrl;

    public void enqueueSummarizationForTask(String taskIdStr, String summarizer) {
        UUID taskId;
        try {
            taskId = UUID.fromString(taskIdStr);
        } catch (RuntimeException e) {
            log.warn("Invalid taskId for summarization enqueue: {}", taskIdStr);
            return;
        }
        if (summarizer == null || summarizer.isBlank()) {
            log.info("Summarization skipped: taskId={} — summarizer name is blank", taskIdStr);
            return;
        }
        if (!summarizerProviderRepository.isSummarizerEnabled(summarizer)) {
            log.info("Summarization skipped: route «{}» disabled (enabled=false), taskId={}", summarizer, taskIdStr);
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
                    String failMsg = "Не удалось отправить пакет во внешний контур (dispatch не выполнен)";
                    externalSummarizationPendingService.failPendingWindow(taskId, failMsg);
                    throw new SummarizationEnqueueException(failMsg);
                }
            } catch (SummarizationEnqueueException e) {
                throw e;
            } catch (RuntimeException e) {
                log.warn("Failed to register external summarization window for task {}", taskIdStr, e);
                throw new SummarizationEnqueueException(
                        e.getMessage() != null ? e.getMessage() : "External summarization registration failed");
            }
            return;
        }
        String customPrompt = fetchStoredCustomPrompt(taskId).orElse(null);
        try {
            kafkaOutboxService.sendSummarizationEvent(taskIdStr,
                    new SummarizationTaskEvent(taskIdStr, summarizer, customPrompt));
            log.info("Enqueued summarization: taskId={}, summarizer={} (customPrompt={})",
                    taskIdStr, summarizer, customPrompt != null);
        } catch (RuntimeException e) {
            log.warn("Failed to enqueue summarization for task {}", taskIdStr, e);
        }
    }

    private Optional<String> fetchStoredCustomPrompt(UUID taskId) {
        String base = appBaseUrl != null ? appBaseUrl.replaceAll("/$", "") : "";
        if (base.isBlank()) {
            return Optional.empty();
        }
        String url = base + "/api/v1/loadtest/internal/custom-summarization-prompt/" + taskId;
        try {
            return webClient.get()
                    .uri(url)
                    .exchangeToMono(response -> {
                        if (response.statusCode().equals(HttpStatus.NO_CONTENT)) {
                            return Mono.just(Optional.<String>empty());
                        }
                        if (!response.statusCode().is2xxSuccessful()) {
                            return Mono.just(Optional.<String>empty());
                        }
                        return response.bodyToMono(new ParameterizedTypeReference<Map<String, String>>() {})
                                .map(body -> {
                                    if (body == null) {
                                        return Optional.<String>empty();
                                    }
                                    String p = body.get("customPrompt");
                                    if (p == null || p.isBlank()) {
                                        return Optional.<String>empty();
                                    }
                                    return Optional.of(p.trim());
                                })
                                .defaultIfEmpty(Optional.empty());
                    })
                    .block();
        } catch (RuntimeException e) {
            log.debug("No custom summarization prompt for taskId={}: {}", taskId, e.getMessage());
            return Optional.empty();
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
        } catch (RuntimeException e) {
            log.warn("Failed to trigger external dispatch for taskId={}: {}", taskId, e.getMessage());
            return false;
        }
    }
}
