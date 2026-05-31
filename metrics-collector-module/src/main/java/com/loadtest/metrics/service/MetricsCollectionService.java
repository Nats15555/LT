package com.loadtest.metrics.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadtest.metrics.config.MetricsCollectorProperties;
import com.loadtest.metrics.dto.MetricsCollectionRequest;
import com.loadtest.metrics.dto.MetricsCollectionResponse;
import com.loadtest.metrics.util.TestSummaryConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class MetricsCollectionService {

    private final WebClient webClient = WebClient.builder()
            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MetricsSummarizationService summarizationService;
    private final MetricsCollectorProperties properties;

    public MetricsCollectionService(MetricsSummarizationService summarizationService,
                                    MetricsCollectorProperties properties) {
        this.summarizationService = summarizationService;
        this.properties = properties;
    }

    private String resolveUrl(String url) {
        if (url == null || url.isBlank() || properties.getHostOverrides() == null || properties.getHostOverrides().isEmpty()) {
            return url;
        }
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null) {
                return url;
            }
            String override = properties.getHostOverrides().get(host);
            if (override == null) {
                return url;
            }
            URI replaced = new URI(uri.getScheme(), null, override, uri.getPort(), uri.getPath(), uri.getQuery(), uri.getFragment());
            String resolved = replaced.toASCIIString();
            log.debug("Resolved URL host {} -> {}: {}", host, override, resolved);
            return resolved;
        } catch (URISyntaxException | IllegalArgumentException e) {
            log.warn("Failed to apply host override for URL {}: {}", url, e.getMessage());
            return url;
        }
    }

    public MetricsCollectionResponse collectMetrics(MetricsCollectionRequest request) {
        log.info("Starting metrics collection for taskId: {}, requests: {}",
                request.taskId(), request.requests() != null ? request.requests().size() : 0);

        long startTime = System.currentTimeMillis();
        Map<String, Object> collectedMetrics = new HashMap<>();
        CollectionState state = new CollectionState();

        applyDelayIfNeeded(request);

        try {
            collectAllRequests(request, collectedMetrics, state);
            MetricsCollectionResponse.SummaryResult summary = summarizationService.summarize(
                    request.taskId(), collectedMetrics);
            return toResponse(request.taskId(), state.status(), state.message(), collectedMetrics, summary, startTime);
        } catch (RuntimeException e) {
            log.error("Error during metrics collection for taskId: {}", request.taskId(), e);
            return toResponse(
                    request.taskId(),
                    TestSummaryConstants.STATUS_FAILED,
                    "Failed to collect metrics: " + e.getMessage(),
                    collectedMetrics,
                    null,
                    startTime);
        }
    }

    private void applyDelayIfNeeded(MetricsCollectionRequest request) {
        Integer delaySeconds = request.delaySeconds();
        if (delaySeconds == null || delaySeconds <= 0) {
            return;
        }
        log.info("Waiting {} seconds before collecting metrics for taskId: {}", delaySeconds, request.taskId());
        try {
            TimeUnit.SECONDS.sleep(delaySeconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for delay", e);
        }
    }

    private void collectAllRequests(
            MetricsCollectionRequest request,
            Map<String, Object> collectedMetrics,
            CollectionState state) {
        for (MetricsCollectionRequest.MetricsRequestItem req : request.requests()) {
            collectSingleRequest(request.taskId(), req, collectedMetrics, state);
        }
    }

    private void collectSingleRequest(
            String taskId,
            MetricsCollectionRequest.MetricsRequestItem req,
            Map<String, Object> collectedMetrics,
            CollectionState state) {
        String key = metricKey(req);
        try {
            long reqStart = System.currentTimeMillis();
            Map<String, Object> result = executeRequest(req);
            long reqMs = System.currentTimeMillis() - reqStart;
            collectedMetrics.put(key, result);
            log.info("Collected metrics from '{}' | {} {} | {} ms | taskId={}",
                    key, req.method(), req.url(), reqMs, taskId);
        } catch (RuntimeException e) {
            logCollectionFailure(key, req, e);
            collectedMetrics.put(key, Map.of("error", e.getMessage()));
            state.markPartial();
        }
    }

    private void logCollectionFailure(String key, MetricsCollectionRequest.MetricsRequestItem req, RuntimeException e) {
        String hint = buildResolveHint(req.url(), e);
        if (e instanceof WebClientResponseException ex) {
            log.error("Failed to collect from '{}' | {} {} | status: {} | body: {}{}",
                    key, req.method(), req.url(), ex.getStatusCode(), ex.getResponseBodyAsString(), hint);
        } else {
            log.error("Failed to collect from '{}' | {} {} | error: {}{}",
                    key, req.method(), req.url(), e.getMessage(), hint);
        }
    }

    private static MetricsCollectionResponse toResponse(
            String taskId,
            String status,
            String message,
            Map<String, Object> metrics,
            MetricsCollectionResponse.SummaryResult summary,
            long startTime) {
        return new MetricsCollectionResponse(
                taskId,
                status,
                message,
                metrics,
                summary,
                startTime,
                System.currentTimeMillis());
    }

    public String getEffectiveUrl(MetricsCollectionRequest.MetricsRequestItem req) {
        String url = req.url();
        if (Objects.requireNonNullElse(url, "").isBlank()) {
            return "";
        }
        return buildFullUri(resolveUrl(url), req.queryParams());
    }

    private static String buildFullUri(String baseUrl, Object queryParams) {
        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromHttpUrl(baseUrl);
        if (queryParams instanceof String q) {
            appendAmpersandQueryString(uriBuilder, q);
        } else if (queryParams instanceof Map<?, ?> qMap) {
            appendMapQueryParams(uriBuilder, qMap);
        }
        return uriBuilder.build().toUriString();
    }

    private static void appendAmpersandQueryString(UriComponentsBuilder uriBuilder, String queryString) {
        if (queryString.isBlank()) {
            return;
        }
        for (String part : queryString.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                uriBuilder.queryParam(part.substring(0, eq), part.substring(eq + 1));
            }
        }
    }

    private static void appendMapQueryParams(UriComponentsBuilder uriBuilder, Map<?, ?> qMap) {
        qMap.forEach((k, v) -> uriBuilder.queryParam(String.valueOf(k), v != null ? v.toString() : ""));
    }

    private static String buildResolveHint(String requestUrl, Exception e) {
        String msg = e.getMessage();
        if (msg == null || (!msg.contains("Failed to resolve") && !msg.contains("Name or service not known"))) {
            return "";
        }
        String host = extractHostFromUrl(requestUrl);
        if (host == null) {
            return "";
        }
        return " | Tip: if metrics-collector runs on host and " + host
                + " is a Docker service, set metrics.host-overrides." + host + "=localhost";
    }

    private static String extractHostFromUrl(String requestUrl) {
        try {
            return URI.create(requestUrl).getHost();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> executeRequest(MetricsCollectionRequest.MetricsRequestItem req) {
        String url = req.url();
        if (Objects.requireNonNullElse(url, "").isBlank()) {
            throw new IllegalArgumentException("Request URL is required");
        }
        String fullUri = buildFullUri(resolveUrl(url), req.queryParams());

        HttpMethod method = HttpMethod.valueOf(
                req.method() != null && !req.method().isBlank() ? req.method().toUpperCase() : "GET");

        log.debug("Executing metrics request: {} {} (taskId from context)", method, fullUri);

        WebClient.RequestBodySpec spec = webClient.method(method)
                .uri(fullUri)
                .contentType(MediaType.APPLICATION_JSON);

        if (req.headers() != null && !req.headers().isEmpty()) {
            req.headers().forEach(spec::header);
        }

        String responseBody = fetchResponseBody(spec, method, req.body());
        return parseResponseBody(responseBody);
    }

    private String fetchResponseBody(WebClient.RequestBodySpec spec, HttpMethod method, Object body) {
        if (method == HttpMethod.GET || method == HttpMethod.HEAD || body == null) {
            return spec.retrieve().bodyToMono(String.class).block();
        }
        String bodyStr = body instanceof String s ? s : serializeRequestBody(body);
        return spec.bodyValue(bodyStr).retrieve().bodyToMono(String.class).block();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseResponseBody(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return Map.of("_raw", "");
        }
        try {
            return objectMapper.readValue(responseBody, Map.class);
        } catch (JsonProcessingException e) {
            return Map.of("_raw", responseBody);
        }
    }

    private String serializeRequestBody(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid request body JSON", e);
        }
    }

    private static String metricKey(MetricsCollectionRequest.MetricsRequestItem req) {
        return req.name() != null && !req.name().isBlank() ? req.name() : req.url();
    }

    private static final class CollectionState {
        private String status = "SUCCESS";
        private String message = "Metrics collected successfully";

        private void markPartial() {
            status = "PARTIAL";
            message = "Some requests failed to collect metrics";
        }

        private String status() {
            return status;
        }

        private String message() {
            return message;
        }
    }
}
