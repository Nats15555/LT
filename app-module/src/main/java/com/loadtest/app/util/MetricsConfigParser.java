package com.loadtest.app.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadtest.app.dto.TestTaskMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class MetricsConfigParser {

    private final ObjectMapper objectMapper;
    private final MetricsConfigSchemaValidator schemaValidator;

    public TestTaskMessage.MetricsConfig parseMetricsConfigRequests(String metricsConfigJson) {
        if (metricsConfigJson == null || metricsConfigJson.trim().isEmpty()) {
            throw new IllegalArgumentException("Metrics configuration JSON is required");
        }
        final JsonNode root;
        try {
            root = objectMapper.readTree(metricsConfigJson);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid metrics configuration JSON: " + e.getMessage(), e);
        }
        schemaValidator.validate(root);

        TestTaskMessage.MetricsConfig parsed = objectMapper.convertValue(root, TestTaskMessage.MetricsConfig.class);
        List<TestTaskMessage.MetricsConfig.MetricsRequest> requests = parsed.requests();
        if (requests == null || requests.isEmpty()) {
            throw new IllegalArgumentException("'requests' must contain at least one item");
        }
        List<TestTaskMessage.MetricsConfig.MetricsRequest> normalized = new ArrayList<>(requests.size());
        for (TestTaskMessage.MetricsConfig.MetricsRequest req : requests) {
            if (req.url() == null || req.url().trim().isEmpty()) {
                throw new IllegalArgumentException("Each request must have 'url'");
            }
            String name = req.name();
            if (name == null || name.trim().isEmpty()) {
                name = req.url();
            }
            normalized.add(new TestTaskMessage.MetricsConfig.MetricsRequest(
                    name, req.method(), req.url(), req.headers(), req.queryParams(), req.body()));
        }
        return new TestTaskMessage.MetricsConfig(parsed.delaySeconds(), normalized);
    }
}
