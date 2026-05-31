package com.loadtest.app.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadtest.app.persistence.SummarizerModelEntity;
import com.loadtest.app.persistence.SummarizerModelRepository;
import com.loadtest.app.persistence.TestTaskHistoryEntity;
import com.loadtest.app.persistence.TestTaskHistoryRepository;
import com.loadtest.app.util.ApiJsonKeys;
import com.loadtest.app.util.ApiMessages;
import com.loadtest.app.util.ApiResponseValues;
import com.loadtest.app.util.SummarizerProviders;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExternalLlmDispatchService {

    private final ExternalSummarizationCallbackService externalSummarizationCallbackService;
    private final SummarizerModelRepository summarizerModelRepository;
    private final TestTaskHistoryRepository historyRepository;
    private final ObjectMapper objectMapper;
    private final CustomSummarizationPromptStore customSummarizationPromptStore;

    @Value("${loadtest.external-llm.receiver-url:}")
    private String fallbackReceiverUrl;

    @Value("${loadtest.external-llm.rewrite-docker-service-host-to:}")
    private String rewriteDockerServiceHostTo;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public Map<String, Object> dispatchPackage(UUID taskId) {
        return dispatchPackage(taskId, null);
    }

    public Map<String, Object> dispatchPackage(UUID taskId, String customPromptOverride) {
        String prompt = resolvePrompt(taskId, customPromptOverride);
        Map<String, Object> pkg = externalSummarizationCallbackService.buildPackage(taskId, prompt);
        String url = resolveReceiverUrl(requireExternalSummarizerModel(taskId));
        return postPackage(url, pkg, taskId);
    }

    private String resolvePrompt(UUID taskId, String customPromptOverride) {
        if (customPromptOverride != null && !customPromptOverride.isBlank()) {
            return customPromptOverride;
        }
        return customSummarizationPromptStore.consume(taskId).orElse(null);
    }

    private SummarizerModelEntity requireExternalSummarizerModel(UUID taskId) {
        TestTaskHistoryEntity history = historyRepository.findById(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        ApiMessages.ExternalSummarization.RUN_NOT_FOUND));
        String summarizerName = history.getSummarizerName();
        if (summarizerName == null || summarizerName.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    ApiMessages.ExternalSummarization.SUMMARIZER_NAME_MISSING);
        }
        SummarizerModelEntity model = summarizerModelRepository.findByName(summarizerName.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        ApiMessages.ExternalSummarization.LLM_ROUTE_NOT_FOUND));
        if (!SummarizerProviders.EXTERNAL.equalsIgnoreCase(model.getProvider())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.ExternalSummarization.ROUTE_NOT_EXTERNAL);
        }
        return model;
    }

    private String resolveReceiverUrl(SummarizerModelEntity model) {
        String url = firstNonBlank(model.getBaseUrl(), fallbackReceiverUrl);
        if (url == null || url.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Для EXTERNAL укажите полный URL приёма пакета (поле baseUrl у маршрута в summarizer_models) или задайте loadtest.external-llm.receiver-url");
        }
        return rewriteDockerServiceHostname(url);
    }

    private Map<String, Object> postPackage(String url, Map<String, Object> pkg, UUID taskId) {
        try {
            HttpResponse<String> response = executeHttpPost(url, pkg);
            ensureHttpSuccess(response, taskId);
            ensureReceiverAccepted(response, taskId);
            return dispatchSuccessBody(url);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failDispatch(taskId, url, e);
        } catch (IOException | RuntimeException e) {
            throw failDispatch(taskId, url, e);
        }
    }

    private HttpResponse<String> executeHttpPost(String url, Map<String, Object> pkg)
            throws IOException, InterruptedException {
        String json = objectMapper.writeValueAsString(pkg);
        log.info("External LLM dispatch POST to {}", url);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private void ensureHttpSuccess(HttpResponse<String> response, UUID taskId) {
        int statusCode = response.statusCode();
        if (statusCode >= 200 && statusCode < 300) {
            return;
        }
        externalSummarizationCallbackService.failPendingWindow(taskId,
                "Не удалось доставить пакет во внешний контур: HTTP " + statusCode);
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "External receiver HTTP " + statusCode);
    }

    private void ensureReceiverAccepted(HttpResponse<String> response, UUID taskId) {
        JsonNode root = parseResponseBody(response.body());
        if (root.path(ApiJsonKeys.RECEIVED).asBoolean(false)) {
            return;
        }
        String reason = root.path("reason").asText("");
        String message = "Внешний контур не принял пакет" + (reason.isBlank() ? "" : (": " + reason));
        externalSummarizationCallbackService.failPendingWindow(taskId, message);
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, message);
    }

    private JsonNode parseResponseBody(String body) {
        if (body != null && !body.isBlank()) {
            try {
                return objectMapper.readTree(body);
            } catch (JsonProcessingException e) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Invalid external receiver JSON");
            }
        }
        return objectMapper.nullNode();
    }

    private static Map<String, Object> dispatchSuccessBody(String url) {
        return Map.of(
                ApiJsonKeys.STATUS, ApiResponseValues.STATUS_SUCCESS,
                ApiJsonKeys.RECEIVED, true,
                ApiJsonKeys.RECEIVER_URL, url);
    }

    private ResponseStatusException failDispatch(UUID taskId, String url, Exception e) {
        String detail = describeExceptionChain(e);
        log.warn("External LLM dispatch failed: taskId={}, url={}, detail={}", taskId, url, detail, e);
        externalSummarizationCallbackService.failPendingWindow(taskId,
                "Не удалось доставить пакет во внешний контур: " + detail);
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Dispatch failed: " + detail);
    }

    private String rewriteDockerServiceHostname(String url) {
        if (url == null || rewriteDockerServiceHostTo == null || rewriteDockerServiceHostTo.isBlank()) {
            return url;
        }
        if (!url.contains("external-llm-mock")) {
            return url;
        }
        String to = rewriteDockerServiceHostTo.trim();
        String rewritten = url.replace("external-llm-mock", to);
        if (!rewritten.equals(url)) {
            log.info("Rewrote ingest URL host external-llm-mock → {} (loadtest.external-llm.rewrite-docker-service-host-to)", to);
        }
        return rewritten;
    }

    private static String describeExceptionChain(Throwable e) {
        if (e == null) {
            return "(null)";
        }
        StringBuilder sb = new StringBuilder();
        Throwable cur = e;
        int depth = 0;
        while (cur != null && depth++ < 6) {
            if (!sb.isEmpty()) {
                sb.append(" ← ");
            }
            sb.append(cur.getClass().getSimpleName());
            String m = cur.getMessage();
            if (m != null && !m.isBlank()) {
                sb.append(": ").append(m);
            }
            cur = cur.getCause();
        }
        return sb.toString();
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a.trim();
        }
        if (b != null && !b.isBlank()) {
            return b.trim();
        }
        return null;
    }
}
