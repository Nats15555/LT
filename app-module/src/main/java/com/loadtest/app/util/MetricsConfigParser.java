package com.loadtest.app.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadtest.app.dto.TestTaskMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class MetricsConfigParser {

    private final ObjectMapper objectMapper;
    private final MetricsConfigSchemaValidator schemaValidator;

    public TestTaskMessage.MetricsConfig parseMetricsConfigRequests(String metricsConfigJson) throws Exception {
        if (metricsConfigJson == null || metricsConfigJson.trim().isEmpty()) {
            throw new IllegalArgumentException("Metrics configuration JSON is required");
        }
        JsonNode root = objectMapper.readTree(metricsConfigJson);
        schemaValidator.validate(root);

        TestTaskMessage.MetricsConfig config = objectMapper.convertValue(root, TestTaskMessage.MetricsConfig.class);
        if (config.getDelaySeconds() == null) {
            config.setDelaySeconds(0);
        }
        List<TestTaskMessage.MetricsConfig.MetricsRequest> requests = config.getRequests();
        if (requests == null || requests.isEmpty()) {
            throw new IllegalArgumentException("'requests' must contain at least one item");
        }
        for (TestTaskMessage.MetricsConfig.MetricsRequest req : requests) {
            if (req.getUrl() == null || req.getUrl().trim().isEmpty()) {
                throw new IllegalArgumentException("Each request must have 'url'");
            }
            if (req.getName() == null || req.getName().trim().isEmpty()) {
                req.setName(req.getUrl());
            }
            if (req.getMethod() == null || req.getMethod().trim().isEmpty()) {
                req.setMethod("GET");
            }
        }
        return config;
    }
}
