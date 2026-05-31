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

    private static final String LOG_REQUEST_FAILED =
            "OpenAI-compatible запрос не выполнен url={} model={}: {}";

    private final WebClient httpClient;

    public OpenAiCompatibleClient(@Qualifier("summarizationLlmWebClient") WebClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public String summarize(SummarizerConfig config, String prompt) {
        String route = routeLabel(config);
        String baseUrl = requireBaseUrl(config, route);
        String modelId = requireModelId(config, route);
        String url = chatCompletionsUrl(baseUrl);
        WebClient.RequestHeadersSpec<?> request = buildChatRequest(config, url, modelId, prompt);

        log.info("OpenAI-compatible HTTP: POST {} model={} promptChars={}", url, modelId, prompt.length());
        try {
            Map<?, ?> response = executeRequest(request);
            return extractAssistantContent(response);
        } catch (LlmSummarizationException e) {
            log.error(LOG_REQUEST_FAILED, url, modelId, e.getMessage(), e);
            throw e;
        } catch (RuntimeException e) {
            log.error(LOG_REQUEST_FAILED, url, modelId, e.getMessage(), e);
            throw new LlmSummarizationException(e.getMessage(), e);
        } catch (Exception e) {
            log.error(LOG_REQUEST_FAILED, url, modelId, e.getMessage(), e);
            throw new LlmSummarizationException("LLM request failed", e);
        }
    }

    private static String routeLabel(SummarizerConfig config) {
        return config.getName() != null && !config.getName().isBlank()
                ? config.getName()
                : "(id=" + config.getId() + ")";
    }

    private static String requireBaseUrl(SummarizerConfig config, String route) {
        String baseUrl = config.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            log.warn("Маршрут LLM '{}': не задан base_url в summarizer_models — запрос к API не выполняется, суммаризация не будет выполнена.",
                    route);
            throw new IllegalArgumentException("Summarizer base_url is not configured for route: " + route);
        }
        return baseUrl.replaceFirst("/+$", "");
    }

    private static String requireModelId(SummarizerConfig config, String route) {
        String modelId = config.getModelId();
        if (modelId == null || modelId.isBlank()) {
            log.warn("Маршрут LLM '{}': не задан model_id — запрос к API не выполняется, суммаризация не будет выполнена.",
                    route);
            throw new IllegalArgumentException("Summarizer model_id is not configured for route: " + route);
        }
        return modelId;
    }

    private static String chatCompletionsUrl(String baseUrl) {
        return baseUrl + "/v1/chat/completions";
    }

    private WebClient.RequestHeadersSpec<?> buildChatRequest(
            SummarizerConfig config,
            String url,
            String modelId,
            String prompt) {
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
        return spec;
    }

    private Map<?, ?> executeRequest(WebClient.RequestHeadersSpec<?> request) {
        Map<?, ?> response = request.retrieve()
                .bodyToMono(Map.class)
                .block();
        if (response == null) {
            throw new LlmSummarizationException("Empty response from LLM");
        }
        return response;
    }

    private String extractAssistantContent(Map<?, ?> response) {
        Object choices = response.get("choices");
        if (!(choices instanceof List<?> list) || list.isEmpty()) {
            throw unexpectedStructure(response);
        }
        Object firstChoice = list.get(0);
        if (!(firstChoice instanceof Map<?, ?> choice)) {
            throw unexpectedStructure(response);
        }
        Object message = choice.get("message");
        if (!(message instanceof Map<?, ?> messageMap)) {
            throw unexpectedStructure(response);
        }
        Object content = messageMap.get("content");
        if (content == null) {
            throw unexpectedStructure(response);
        }
        log.info("OpenAI-compatible HTTP: ответ получен, contentChars={}", content.toString().length());
        return content.toString().trim();
    }

    private static LlmSummarizationException unexpectedStructure(Map<?, ?> response) {
        return new LlmSummarizationException("Unexpected response structure: " + response.keySet());
    }

    public static class LlmSummarizationException extends RuntimeException {

        public LlmSummarizationException(String message) {
            super(message);
        }

        public LlmSummarizationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
