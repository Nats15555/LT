package com.loadtest.metrics.service;

import com.loadtest.metrics.dto.MetricsCollectionResponse;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MetricsSummarizationServiceTest {

    @Test
    void summarize_successAndErrorLikePayloadBranches() {
        MetricsSummarizationService service = new MetricsSummarizationService();

        MetricsCollectionResponse.SummaryResult ok = service.summarize("t1", Map.of(
                "a", Map.of("v", 1),
                "b", Map.of("error", "bad")
        ));
        assertThat(ok.status()).isEqualTo("SUCCESS");
        assertThat(ok.summary()).contains("2 endpoint");
        assertThat(ok.details()).containsKey("totalEndpoints");

        MetricsCollectionResponse.SummaryResult empty = service.summarize("t2", Map.of());
        assertThat(empty.status()).isEqualTo("SUCCESS");
        assertThat(empty.summary()).contains("0 endpoint");
    }

    @Test
    void summarize_whenMetricsNull_returnsFailedBranch() {
        MetricsSummarizationService service = new MetricsSummarizationService();

        MetricsCollectionResponse.SummaryResult failed = service.summarize("t3", null);
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.summary()).contains("Failed to summarize metrics");
    }

    @Test
    void summarize_ignoresNonMapMetricValue_hitsLine48FalseBranch() {
        MetricsSummarizationService service = new MetricsSummarizationService();

        MetricsCollectionResponse.SummaryResult result = service.summarize("t4", Map.of(
                "map-ok", Map.of("v", 1),
                "plain", "raw-value"
        ));

        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.summary()).contains("2 endpoint(s), 1 successful, 1 failed");
    }
}
