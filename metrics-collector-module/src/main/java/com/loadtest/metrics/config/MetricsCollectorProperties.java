package com.loadtest.metrics.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "metrics")
public class MetricsCollectorProperties {
    private Map<String, String> hostOverrides = new HashMap<>();
}
