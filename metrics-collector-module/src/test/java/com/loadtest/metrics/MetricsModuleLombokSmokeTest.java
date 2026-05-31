package com.loadtest.metrics;

import com.loadtest.metrics.config.MetricsCollectorProperties;
import com.loadtest.metrics.dto.MetricsCollectionEvent;
import com.loadtest.metrics.dto.MetricsCollectionRequest;
import com.loadtest.metrics.dto.MetricsCollectionResponse;
import com.loadtest.metrics.dto.SummarizationTaskEvent;
import com.loadtest.metrics.persistence.TaskMetricsConfigRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MetricsModuleLombokSmokeTest {

    @Test
    void dtoAndProperties_lombokMethodsCovered() {
        MetricsCollectionEvent e1 = new MetricsCollectionEvent("t", 1L, 2L);
        MetricsCollectionEvent e2 = new MetricsCollectionEvent("t", 1L, 2L);
        assertThat(e1).isEqualTo(e2);
        assertThat(e1.hashCode()).isEqualTo(e2.hashCode());
        assertThat(e1.toString()).contains("taskId");

        MetricsCollectionRequest.MetricsRequestItem item =
                new MetricsCollectionRequest.MetricsRequestItem("n", "POST", "http://x", Map.of("h", "v"), "a=1", Map.of("k", "v"));
        MetricsCollectionRequest req = new MetricsCollectionRequest("t", List.of(item), 1, 10L, 20L);
        assertThat(req.requests()).hasSize(1);

        MetricsCollectionResponse.SummaryResult sr = new MetricsCollectionResponse.SummaryResult(
                "SUCCESS", "ok", Map.of("k", "v"));
        MetricsCollectionResponse resp = new MetricsCollectionResponse(
                "t", "SUCCESS", "ok", Map.of("m", 1), sr, 1L, 2L);
        assertThat(resp.toString()).contains("SUCCESS");

        SummarizationTaskEvent s1 = new SummarizationTaskEvent("t", "sum");
        SummarizationTaskEvent s2 = new SummarizationTaskEvent("t", "sum");
        assertThat(s1).isEqualTo(s2);

        MetricsCollectorProperties props = new MetricsCollectorProperties();
        props.setHostOverrides(Map.of("prometheus", "localhost"));
        assertThat(props.getHostOverrides()).containsEntry("prometheus", "localhost");

        TaskMetricsConfigRepository.TaskMetricsConfig cfg = new TaskMetricsConfigRepository.TaskMetricsConfig("{\"a\":1}");
        assertThat(cfg.metricsConfigJson()).isEqualTo("{\"a\":1}");
    }
}
