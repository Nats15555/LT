package com.loadtest.metrics.model;

import com.loadtest.metrics.dto.MetricsCollectionEvent;
import com.loadtest.metrics.dto.MetricsCollectionRequest;
import com.loadtest.metrics.dto.MetricsCollectionResponse;
import com.loadtest.metrics.dto.SummarizationTaskEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MetricsLombokBranchExhaustiveTest {

    @Test
    void summarizationTaskEvent_recordEqualsBranches() {
        SummarizationTaskEvent base = new SummarizationTaskEvent("t", "s");
        assertThat(base).isEqualTo(new SummarizationTaskEvent("t", "s"));
        assertThat(base).isNotEqualTo(new SummarizationTaskEvent("x", "s"));
    }

    @Test
    void metricsCollectionEvent_recordEquals() {
        MetricsCollectionEvent base = new MetricsCollectionEvent("t", 1L, 2L);
        assertThat(base).isEqualTo(new MetricsCollectionEvent("t", 1L, 2L));
        assertThat(base).isNotEqualTo(new MetricsCollectionEvent("x", 1L, 2L));
    }

    @Test
    void metricsCollectionRequest_recordEquals() {
        List<MetricsCollectionRequest.MetricsRequestItem> requests = List.of(
                new MetricsCollectionRequest.MetricsRequestItem("n", "GET", "http://u", null, null, null));
        MetricsCollectionRequest base = new MetricsCollectionRequest("t", requests, 0, null, null);
        assertThat(base).isEqualTo(new MetricsCollectionRequest("t", requests, 0, null, null));
        assertThat(base).isNotEqualTo(new MetricsCollectionRequest("x", requests, 0, null, null));
        assertThat(base.taskId()).isEqualTo("t");
        assertThat(base.delaySeconds()).isZero();
    }

    @Test
    void metricsCollectionResponse_recordEquals() {
        MetricsCollectionResponse.SummaryResult summary =
                new MetricsCollectionResponse.SummaryResult("SUCCESS", "s", Map.of("k", "v"));
        MetricsCollectionResponse base = new MetricsCollectionResponse(
                "t", "SUCCESS", "m", Map.of("k", "v"), summary, 1L, 2L);
        assertThat(base).isEqualTo(new MetricsCollectionResponse(
                "t", "SUCCESS", "m", Map.of("k", "v"), summary, 1L, 2L));
        assertThat(base).isNotEqualTo(new MetricsCollectionResponse(
                "x", "SUCCESS", "m", Map.of("k", "v"), summary, 1L, 2L));
        assertThat(base.status()).isEqualTo("SUCCESS");
    }

    @Test
    void summaryResult_recordEquals() {
        MetricsCollectionResponse.SummaryResult base =
                new MetricsCollectionResponse.SummaryResult("SUCCESS", "s", Map.of("k", "v"));
        assertThat(base).isEqualTo(new MetricsCollectionResponse.SummaryResult("SUCCESS", "s", Map.of("k", "v")));
        assertThat(base).isNotEqualTo(new MetricsCollectionResponse.SummaryResult("FAILED", "s", Map.of("k", "v")));
    }
}
