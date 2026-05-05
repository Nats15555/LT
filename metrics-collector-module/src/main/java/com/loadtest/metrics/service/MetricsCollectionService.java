package com.loadtest.metrics.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadtest.metrics.config.MetricsCollectorProperties;
import com.loadtest.metrics.dto.MetricsCollectionRequest;
import com.loadtest.metrics.dto.MetricsCollectionResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
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
            if (host == null) return url;
            String override = properties.getHostOverrides().get(host);
            if (override == null) return url;
            URI replaced = new URI(uri.getScheme(), null, override, uri.getPort(), uri.getPath(), uri.getQuery(), uri.getFragment());
            String resolved = replaced.toASCIIString();
            log.debug("Resolved URL host {} -> {}: {}", host, override, resolved);
            return resolved;
        } catch (Exception e) {
            log.warn("Failed to apply host override for URL {}: {}", url, e.getMessage());
            return url;
        }
    }

    public MetricsCollectionResponse collectMetrics(MetricsCollectionRequest request) {
        log.info("Starting metrics collection for taskId: {}, requests: {}",
                request.getTaskId(), request.getRequests() != null ? request.getRequests().size() : 0);

        long startTime = System.currentTimeMillis();
        Map<String, Object> collectedMetrics = new HashMap<>();
        String status = "SUCCESS";
        String message = "Metrics collected successfully";

        if (request.getDelaySeconds() != null && request.getDelaySeconds() > 0) {
            log.info("Waiting {} seconds before collecting metrics for taskId: {}",
                    request.getDelaySeconds(), request.getTaskId());
            try {
                TimeUnit.SECONDS.sleep(request.getDelaySeconds());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while waiting for delay", e);
            }
        }

        try {
            for (MetricsCollectionRequest.MetricsRequestItem req : request.getRequests()) {
                String key = req.getName() != null && !req.getName().isBlank() ? req.getName() : req.getUrl();
                try {
                    long reqStart = System.currentTimeMillis();
                    Map<String, Object> result = executeRequest(req);
                    long reqMs = System.currentTimeMillis() - reqStart;
                    collectedMetrics.put(key, result);
                    log.info("Collected metrics from '{}' | {} {} | {} ms | taskId={}",
                            key, req.getMethod(), req.getUrl(), reqMs, request.getTaskId());
                } catch (Exception e) {
                    String hint = buildResolveHint(req.getUrl(), e);
                    if (e instanceof WebClientResponseException ex) {
                        log.error("Failed to collect from '{}' | {} {} | status: {} | body: {}{}", key, req.getMethod(), req.getUrl(), ex.getStatusCode(), ex.getResponseBodyAsString(), hint);
                    } else {
                        log.error("Failed to collect from '{}' | {} {} | error: {}{}", key, req.getMethod(), req.getUrl(), e.getMessage(), hint);
                    }
                    collectedMetrics.put(key, Map.of("error", e.getMessage()));
                    status = "PARTIAL";
                    message = "Some requests failed to collect metrics";
                }
            }

            MetricsCollectionResponse.SummaryResult summary = summarizationService.summarize(
                    request.getTaskId(), collectedMetrics);

            long endTime = System.currentTimeMillis();
            return MetricsCollectionResponse.builder()
                    .taskId(request.getTaskId())
                    .status(status)
                    .message(message)
                    .metrics(collectedMetrics)
                    .summary(summary)
                    .collectionStartTime(startTime)
                    .collectionEndTime(endTime)
                    .build();
        } catch (Exception e) {
            log.error("Error during metrics collection for taskId: {}", request.getTaskId(), e);
            long endTime = System.currentTimeMillis();
            return MetricsCollectionResponse.builder()
                    .taskId(request.getTaskId())
                    .status("FAILED")
                    .message("Failed to collect metrics: " + e.getMessage())
                    .metrics(collectedMetrics)
                    .collectionStartTime(startTime)
                    .collectionEndTime(endTime)
                    .build();
        }
    }

    public String getEffectiveUrl(MetricsCollectionRequest.MetricsRequestItem req) {
        if (req.getUrl() == null || req.getUrl().isBlank()) return "";
        return buildFullUri(resolveUrl(req.getUrl()), req.getQueryParams());
    }

    private static String buildFullUri(String baseUrl, Object queryParams) {
        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromHttpUrl(baseUrl);
        if (queryParams != null) {
            if (queryParams instanceof String) {
                String q = (String) queryParams;
                if (!q.isBlank()) {
                    for (String part : q.split("&")) {
                        int eq = part.indexOf('=');
                        if (eq > 0) {
                            uriBuilder.queryParam(part.substring(0, eq), part.substring(eq + 1));
                        }
                    }
                }
            } else if (queryParams instanceof Map) {
                Map<?, ?> qMap = (Map<?, ?>) queryParams;
                qMap.forEach((k, v) -> uriBuilder.queryParam(String.valueOf(k), v != null ? v.toString() : ""));
            }
        }
        return uriBuilder.build().toUriString();
    }

    private static String buildResolveHint(String requestUrl, Exception e) {
        String msg = e.getMessage();
        if (msg == null || (!msg.contains("Failed to resolve") && !msg.contains("Name or service not known"))) {
            return "";
        }
        try {
            String host = URI.create(requestUrl).getHost();
            if (host != null) {
                return " | Tip: if metrics-collector runs on host and " + host + " is a Docker service, set metrics.host-overrides." + host + "=localhost";
            }
        } catch (Exception ex) {
            log.debug("Failed to parse request URL while building resolve hint: {}", requestUrl, ex);
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> executeRequest(MetricsCollectionRequest.MetricsRequestItem req) throws Exception {
        String url = req.getUrl();
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Request URL is required");
        }
        String fullUri = buildFullUri(resolveUrl(url), req.getQueryParams());

        HttpMethod method = HttpMethod.valueOf(req.getMethod() != null && !req.getMethod().isBlank() ? req.getMethod().toUpperCase() : "GET");

        log.debug("Executing metrics request: {} {} (taskId from context)", method, fullUri);

        WebClient.RequestBodySpec spec = webClient.method(method)
                .uri(fullUri)
                .contentType(MediaType.APPLICATION_JSON);

        if (req.getHeaders() != null && !req.getHeaders().isEmpty()) {
            req.getHeaders().forEach(spec::header);
        }

        String responseBody;
        if (method == HttpMethod.GET || method == HttpMethod.HEAD || req.getBody() == null) {
            responseBody = spec.retrieve().bodyToMono(String.class).block();
        } else {
            Object body = req.getBody();
            String bodyStr = body instanceof String ? (String) body : objectMapper.writeValueAsString(body);
            responseBody = spec.bodyValue(bodyStr).retrieve().bodyToMono(String.class).block();
        }

        if (responseBody == null || responseBody.isBlank()) {
            return Map.of("_raw", "");
        }
        try {
            return objectMapper.readValue(responseBody, Map.class);
        } catch (Exception e) {
            return Map.of("_raw", responseBody);
        }
    }
}
