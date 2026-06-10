package com.loadtest.metrics.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadtest.metrics.dto.MetricsCollectionRequest;
import com.loadtest.metrics.dto.MetricsCollectionResponse;
import com.loadtest.metrics.service.MetricsCollectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TestMetricsWriterTest {

    @Mock
    private TestMetricsJpaRepository testMetricsJpaRepository;
    @Mock
    private MetricsCollectionService metricsCollectionService;

    private TestMetricsWriter writer;

    @BeforeEach
    void setUp() {
        writer = new TestMetricsWriter(testMetricsJpaRepository, new ObjectMapper(), metricsCollectionService);
    }

    @Test
    void saveMetrics_branches() {
        String taskId = UUID.randomUUID().toString();
        MetricsCollectionRequest req = new MetricsCollectionRequest(
                taskId,
                List.of(
                        new MetricsCollectionRequest.MetricsRequestItem("source-name-very-very-very-very-long", "GET", "http://u", null, "a=1", null),
                        new MetricsCollectionRequest.MetricsRequestItem(null, "GET", "http://u2", null, Map.of("k", "v"), null)),
                0, null, null);
        MetricsCollectionResponse resp = new MetricsCollectionResponse(
                taskId, null, null,
                Map.of(
                        "source-name-very-very-very-very-long", Map.of("v", 1),
                        "http://u2", "raw"),
                null, null, null);
        when(metricsCollectionService.getEffectiveUrl(any())).thenReturn("http://effective");

        int inserted = writer.saveMetrics(taskId, req, resp);
        assertThat(inserted).isEqualTo(2);
    }

    @Test
    void saveMetrics_returnsZeroForInvalidOrEmpty() {
        String validTaskId = UUID.randomUUID().toString();
        MetricsCollectionRequest req = new MetricsCollectionRequest(validTaskId, List.of(), 0, null, null);
        MetricsCollectionResponse empty = new MetricsCollectionResponse(validTaskId, null, null, Map.of(), null, null, null);
        assertThat(writer.saveMetrics(validTaskId, req, empty)).isZero();
        assertThat(writer.saveMetrics("bad", req, new MetricsCollectionResponse(validTaskId, null, null, Map.of("k", 1), null, null, null))).isZero();
    }

    @Test
    void saveMetrics_throwsOnInsertError() {
        String taskId = UUID.randomUUID().toString();
        MetricsCollectionRequest req = new MetricsCollectionRequest(
                taskId,
                List.of(new MetricsCollectionRequest.MetricsRequestItem("n", "GET", "http://u", null, null, null)),
                0, null, null);
        MetricsCollectionResponse resp = new MetricsCollectionResponse(
                taskId, null, null, Map.of("n", Map.of("v", 1)), null, null, null);
        when(metricsCollectionService.getEffectiveUrl(any())).thenReturn("http://effective");
        doThrow(new RuntimeException("db")).when(testMetricsJpaRepository).save(any(TestMetricsEntity.class));
        assertThatThrownBy(() -> writer.saveMetrics(taskId, req, resp))
                .isInstanceOf(TestMetricsWriter.TestMetricsPersistenceException.class);
    }

    @Test
    void saveMetrics_dataSerializationError_hitsLines59_60() throws Exception {
        ObjectMapper badMapper = mock(ObjectMapper.class);
        TestMetricsWriter local = new TestMetricsWriter(testMetricsJpaRepository, badMapper, metricsCollectionService);
        String taskId = UUID.randomUUID().toString();
        MetricsCollectionRequest req = new MetricsCollectionRequest(
                taskId,
                List.of(new MetricsCollectionRequest.MetricsRequestItem("n", "GET", "http://u", null, null, null)),
                0, null, null);
        MetricsCollectionResponse resp = new MetricsCollectionResponse(
                taskId, null, null, Map.of("n", Map.of("v", 1)), null, null, null);
        when(metricsCollectionService.getEffectiveUrl(any())).thenReturn("http://effective");
        doThrow(new JsonProcessingException("ser-data") {}).when(badMapper).writeValueAsString(eq(Map.of("v", 1)));

        assertThat(local.saveMetrics(taskId, req, resp)).isZero();
    }

    @Test
    void saveMetrics_queryParamsToJsonFallback_hitsLines81_82() throws Exception {
        ObjectMapper badMapper = mock(ObjectMapper.class);
        TestMetricsWriter local = new TestMetricsWriter(testMetricsJpaRepository, badMapper, metricsCollectionService);
        String taskId = UUID.randomUUID().toString();
        Object badQuery = new Object();
        MetricsCollectionRequest req = new MetricsCollectionRequest(
                taskId,
                List.of(new MetricsCollectionRequest.MetricsRequestItem("n", "GET", "http://u", null, badQuery, null)),
                0, null, null);
        MetricsCollectionResponse resp = new MetricsCollectionResponse(
                taskId, null, null, Map.of("n", Map.of("v", 1)), null, null, null);
        when(metricsCollectionService.getEffectiveUrl(any())).thenReturn("http://effective");
        when(badMapper.writeValueAsString(any())).thenAnswer(inv -> {
            Object arg = inv.getArgument(0);
            if (arg == badQuery) {
                throw new JsonProcessingException("ser-query") {};
            }
            return "{\"v\":1}";
        });

        assertThat(local.saveMetrics(taskId, req, resp)).isEqualTo(1);
    }

    @Test
    void saveMetrics_returnsZeroWhenMetricsMapIsNull_hitsLine38Branch() {
        String taskId = UUID.randomUUID().toString();
        MetricsCollectionRequest req = new MetricsCollectionRequest(
                taskId,
                List.of(new MetricsCollectionRequest.MetricsRequestItem("n", "GET", "http://u", null, null, null)),
                0, null, null);
        MetricsCollectionResponse resp = new MetricsCollectionResponse(taskId, null, null, null, null, null, null);

        assertThat(writer.saveMetrics(taskId, req, resp)).isZero();
    }

    @Test
    void saveMetrics_blankNameUsesUrlAndSkipsWhenNoData_hitsLines50_52() {
        String taskId = UUID.randomUUID().toString();
        MetricsCollectionRequest req = new MetricsCollectionRequest(
                taskId,
                List.of(new MetricsCollectionRequest.MetricsRequestItem("   ", "GET", "http://u3", null, null, null)),
                0, null, null);
        MetricsCollectionResponse resp = new MetricsCollectionResponse(
                taskId, null, null, Map.of("other", Map.of("v", 1)), null, null, null);

        assertThat(writer.saveMetrics(taskId, req, resp)).isZero();
    }
}
