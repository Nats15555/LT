package com.loadtest.summarization.service;

import com.loadtest.summarization.persistence.SummarizerConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class OpenAiCompatibleClient implements SummarizerClient {

    private final WebClient httpClient;

    public OpenAiCompatibleClient(@Qualifier("summarizationLlmWebClient") WebClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public String summarize(SummarizerConfig config, String prompt) {
        String route = config.getName() != null && !config.getName().isBlank() ? config.getName() : "(id=" + config.getId() + ")";
        String baseUrl = config.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            log.warn("Маршрут LLM '{}': не задан base_url в summarizer_models — запрос к API не выполняется, суммаризация не будет выполнена.",
                    route);
            throw new IllegalArgumentException("Summarizer base_url is not configured for route: " + route);
        }
        String modelId = config.getModelId();
        if (modelId == null || modelId.isBlank()) {
            log.warn("Маршрут LLM '{}': не задан model_id — запрос к API не выполняется, суммаризация не будет выполнена.",
                    route);
            throw new IllegalArgumentException("Summarizer model_id is not configured for route: " + route);
        }
        baseUrl = baseUrl.replaceFirst("/+$", "");
        String url = baseUrl + "/v1/chat/completions";

        Map<String, Object> body = Map.of(
                "model", modelId,
                "messages", List.of(Map.of("role", "user", "content", prompt)),
                "max_tokens", 2048
        );

        WebClient.RequestHeadersSpec<?> spec = httpClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body);

        if (config.getApiKeyResolved() != null && !config.getApiKeyResolved().isBlank()) {
            spec = spec.header("Authorization", "Bearer " + config.getApiKeyResolved());
        }

        log.info("OpenAI-compatible HTTP: POST {} model={} promptChars={}", url, modelId, prompt.length());
        try {
            Map<?, ?> response = spec.retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (response == null) {
                throw new RuntimeException("Empty response from LLM");
            }
            Object choices = response.get("choices");
            if (choices instanceof List<?> list && !list.isEmpty()) {
                Object first = list.get(0);
                if (first instanceof Map<?, ?> choice) {
                    Object message = choice.get("message");
                    if (message instanceof Map<?, ?> msg) {
                        Object content = msg.get("content");
                        if (content != null) {
                            log.info("OpenAI-compatible HTTP: ответ получен, contentChars={}", content.toString().length());
                            return content.toString().trim();
                        }
                    }
                }
            }
            throw new RuntimeException("Unexpected response structure: " + response.keySet());
        } catch (Exception e) {
            log.error("OpenAI-compatible запрос не выполнен url={} model={}: {}", url, modelId, e.getMessage(), e);
            throw e instanceof RuntimeException re ? re : new RuntimeException(e);
        }
    }
}
