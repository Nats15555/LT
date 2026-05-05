package com.loadtest.externalmock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@RestController
@RequestMapping("/api/v1/external-llm")
@RequiredArgsConstructor
public class ExternalLlmIngestController {

    private final ObjectMapper objectMapper;

    @Value("${loadtest.callback.base-url}")
    private String callbackBaseUrl;

    @Value("${mock.client-name}")
    private String mockClientName;

    @Value("${mock.wait-minutes:0}")
    private int waitMinutes;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "external-llm-mock-scheduler");
        t.setDaemon(true);
        return t;
    });

    @PostConstruct
    void logConfig() {
        log.info("external-llm-mock: callbackBaseUrl={}, waitMinutes={}, clientName={}", callbackBaseUrl, waitMinutes, mockClientName);
    }

    @PostMapping("/ingest")
    public ResponseEntity<Map<String, Object>> ingest(@RequestBody JsonNode pkg) {
        String taskId = pkg.path("taskId").asText("");
        if (taskId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("received", false, "reason", "Missing taskId"));
        }

        JsonNode metrics = pkg.get("metrics");
        JsonNode artifacts = pkg.get("artifacts");
        if (metrics == null || !metrics.isArray() || artifacts == null || !artifacts.isArray()) {
            return ResponseEntity.ok(Map.of("received", false, "reason", "Missing metrics/artifacts arrays"));
        }

        int metricsCount = metrics.size();
        int artifactsCount = artifacts.size();
        String prompt = pkg.path("summarizationPromptRu").asText("");
        log.info("ingest: taskId={}, metrics={}, artifacts={}, promptChars={}", taskId, metricsCount, artifactsCount, prompt.length());

        ResponseEntity<Map<String, Object>> ack = ResponseEntity.ok(Map.of(
                "received", true,
                "taskId", taskId,
                "metricsCount", metricsCount,
                "artifactsCount", artifactsCount
        ));

        long delay = waitMinutes <= 0 ? 0L : waitMinutes;
        TimeUnit unit = waitMinutes <= 0 ? TimeUnit.SECONDS : TimeUnit.MINUTES;
        scheduler.schedule(() -> callbackSummary(pkg), delay, unit);
        return ack;
    }

    private void callbackSummary(JsonNode pkg) {
        String taskId = pkg.path("taskId").asText("");
        if (taskId.isBlank()) return;

        int nm = pkg.path("metrics").isArray() ? pkg.get("metrics").size() : 0;
        int na = pkg.path("artifacts").isArray() ? pkg.get("artifacts").size() : 0;
        String summarizer = pkg.path("summarizerName").asText("—");
        String prompt = pkg.path("summarizationPromptRu").asText("");
        String promptEcho = prompt.length() > 1200 ? prompt.substring(0, 1200) + "\n…" : prompt;

        String delayNote = waitMinutes <= 0 ? "без задержки (mock.wait-minutes=0)" : ("после задержки " + waitMinutes + " мин");

        String text = """
                ## Краткое содержание
                Отчёт от тестового внешнего контура **%s**. Получен пакет (metrics=%d, artifacts=%d), callback %s. Маршрут: %s.

                ## Промпт (как пришёл из LoadTest)
                %s

                ## Плюсы
                - Push-поток работает: ingest → ack → callback.

                ## Минусы
                - Это шаблон: в реальном контуре здесь должен быть текст вашей LLM по промпту и метрикам.

                ## Предложения
                1. Подставить ответ модели вместо этого блока и отправить его в `POST .../external-llm/summary`.

                ## Итог
                Callback отчёта отправлен в LoadTest.
                """.formatted(mockClientName, nm, na, delayNote, summarizer, promptEcho.isBlank() ? "_(поле summarizationPromptRu пустое)_" : promptEcho);

        String url = (callbackBaseUrl != null ? callbackBaseUrl.replaceAll("/$", "") : "") +
                "/api/v1/loadtest/history/" + taskId + "/external-llm/summary";

        try {
            String json = objectMapper.writeValueAsString(Map.of("text", text));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            log.info("callbackSummary: taskId={}, http={}, body={}", taskId, resp.statusCode(),
                    resp.body() != null && resp.body().length() > 200 ? resp.body().substring(0, 200) : resp.body());
        } catch (Exception e) {
            log.warn("callbackSummary failed for taskId={}: {}", taskId, e.getMessage());
        }
    }
}

