package com.loadtest.app.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadtest.app.persistence.SummarizerModelEntity;
import com.loadtest.app.persistence.SummarizerModelRepository;
import com.loadtest.app.persistence.TestTaskHistoryEntity;
import com.loadtest.app.persistence.TestTaskHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

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

    @Value("${loadtest.external-llm.receiver-url:}")
    private String fallbackReceiverUrl;

    @Value("${loadtest.external-llm.rewrite-docker-service-host-to:}")
    private String rewriteDockerServiceHostTo;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public Map<String, Object> dispatchPackage(UUID taskId) {
        Map<String, Object> pkg = externalSummarizationCallbackService.buildPackage(taskId);

        TestTaskHistoryEntity history = historyRepository.findById(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Прогон не найден"));
        String summarizerName = history.getSummarizerName();
        if (summarizerName == null || summarizerName.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "У прогона не задан summarizer_name");
        }
        SummarizerModelEntity model = summarizerModelRepository.findByName(summarizerName.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Маршрут LLM не найден"));
        if (!"EXTERNAL".equalsIgnoreCase(model.getProvider())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Маршрут не EXTERNAL");
        }

        String url = firstNonBlank(model.getBaseUrl(), fallbackReceiverUrl);
        if (url == null || url.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Для EXTERNAL укажите полный URL приёма пакета (поле baseUrl у маршрута в summarizer_models) или задайте loadtest.external-llm.receiver-url");
        }
        url = rewriteDockerServiceHostname(url);

        try {
            String json = objectMapper.writeValueAsString(pkg);
            log.info("External LLM dispatch POST to {}", url);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                externalSummarizationCallbackService.failPendingWindow(taskId, "Не удалось доставить пакет во внешний контур: HTTP " + resp.statusCode());
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "External receiver HTTP " + resp.statusCode());
            }

            JsonNode root = (resp.body() != null && !resp.body().isBlank()) ? objectMapper.readTree(resp.body()) : objectMapper.nullNode();
            boolean received = root.path("received").asBoolean(false);
            if (!received) {
                String reason = root.path("reason").asText("");
                String msg = "Внешний контур не принял пакет" + (reason.isBlank() ? "" : (": " + reason));
                externalSummarizationCallbackService.failPendingWindow(taskId, msg);
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, msg);
            }

            return Map.of(
                    "status", "success",
                    "received", true,
                    "receiverUrl", url
            );
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            String detail = describeExceptionChain(e);
            log.warn("External LLM dispatch failed: taskId={}, url={}, detail={}", taskId, url, detail, e);
            externalSummarizationCallbackService.failPendingWindow(taskId,
                    "Не удалось доставить пакет во внешний контур: " + detail);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Dispatch failed: " + detail);
        }
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
            if (sb.length() > 0) {
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
