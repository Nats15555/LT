package com.loadtest.app1.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ElasticsearchMetricsExporter {
    
    private final MeterRegistry meterRegistry;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String elasticsearchUrl;
    private final String indexName;
    
    public ElasticsearchMetricsExporter(
            MeterRegistry meterRegistry,
            @Value("${management.metrics.export.elastic.host:elasticsearch}") String elasticHost,
            @Value("${management.metrics.export.elastic.port:9200}") int elasticPort,
            @Value("${management.metrics.export.elastic.index:metrics}") String index) {
        this.meterRegistry = meterRegistry;
        this.objectMapper = new ObjectMapper();
        this.elasticsearchUrl = "http://" + elasticHost + ":" + elasticPort;
        this.indexName = index;
        
        this.webClient = WebClient.builder()
                .baseUrl(elasticsearchUrl)
                .build();
        
        log.info("ElasticsearchMetricsExporter initialized with URL: {}", elasticsearchUrl);
    }
    
    @Scheduled(fixedRate = 15000)
    public void exportMetrics() {
        try {
            List<Map<String, Object>> metrics = collectMetrics();
            if (metrics.isEmpty()) {
                log.debug("No metrics to export");
                return;
            }
            
            sendToElasticsearch(metrics);
            log.debug("Exported {} metrics to Elasticsearch", metrics.size());
            
        } catch (Exception e) {
            log.error("Error exporting metrics to Elasticsearch", e);
        }
    }
    
    private List<Map<String, Object>> collectMetrics() {
        List<Map<String, Object>> metrics = new ArrayList<>();
        String timestamp = Instant.now().toString();
        
        for (Meter meter : meterRegistry.getMeters()) {
            try {
                Map<String, Object> metricDoc = new HashMap<>();
                metricDoc.put("@timestamp", timestamp);
                metricDoc.put("name", meter.getId().getName());
                metricDoc.put("type", meter.getId().getType().toString());

                Map<String, String> tags = new HashMap<>();
                meter.getId().getTags().forEach(tag -> tags.put(tag.getKey(), tag.getValue()));
                metricDoc.put("tags", tags);

                switch (meter.getId().getType()) {
                    case COUNTER:
                        io.micrometer.core.instrument.Counter counter = (io.micrometer.core.instrument.Counter) meter;
                        metricDoc.put("value", counter.count());
                        break;
                    case TIMER:
                        io.micrometer.core.instrument.Timer timer = (io.micrometer.core.instrument.Timer) meter;
                        metricDoc.put("value", timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS));
                        metricDoc.put("count", timer.count());
                        metricDoc.put("mean", timer.mean(java.util.concurrent.TimeUnit.MILLISECONDS));
                        break;
                    case GAUGE:
                        io.micrometer.core.instrument.Gauge gauge = (io.micrometer.core.instrument.Gauge) meter;
                        metricDoc.put("value", gauge.value());
                        break;
                    default:
                        continue;
                }
                
                metrics.add(metricDoc);
            } catch (Exception e) {
                log.warn("Error collecting metric: {}", meter.getId().getName(), e);
            }
        }
        
        return metrics;
    }
    
    private void sendToElasticsearch(List<Map<String, Object>> metrics) {
        try {
            String index = indexName + "-" + java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy.MM.dd"));

            StringBuilder bulkBody = new StringBuilder();
            for (Map<String, Object> metric : metrics) {
                Map<String, Object> indexAction = new HashMap<>();
                indexAction.put("index", Map.of("_index", index));
                bulkBody.append(objectMapper.writeValueAsString(indexAction)).append("\n");
                bulkBody.append(objectMapper.writeValueAsString(metric)).append("\n");
            }
            
            String response = webClient.post()
                    .uri("/_bulk")
                    .header("Content-Type", "application/x-ndjson")
                    .bodyValue(bulkBody.toString())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            
            log.debug("Elasticsearch bulk response: {}", response);
            
        } catch (Exception e) {
            log.error("Error sending metrics to Elasticsearch", e);
            throw new RuntimeException("Failed to send metrics to Elasticsearch", e);
        }
    }
}
