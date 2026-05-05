package com.loadtest.execution.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadtest.execution.dto.MetricsCollectionEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import org.springframework.kafka.support.SendResult;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KafkaOutboxServiceTest {

    @Mock private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    @Mock private KafkaTemplate<String, MetricsCollectionEvent> kafkaTemplate;

    private KafkaOutboxService service;

    @BeforeEach
    void setUp() {
        reset(jdbcTemplate, kafkaTemplate);
        service = new KafkaOutboxService(jdbcTemplate, new ObjectMapper(), kafkaTemplate);
        ReflectionTestUtils.setField(service, "metricsCollectionTasksTopic", "metrics-topic");
        ReflectionTestUtils.setField(service, "retryDelayMs", 1000L);
        ReflectionTestUtils.setField(service, "batchSize", 10);
    }

    @Test
    void ensureTable_runsDDL() {
        service.ensureTable();
        ArgumentCaptor<String> ddl = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(2)).execute(ddl.capture());
        assertThat(ddl.getAllValues()).anyMatch(s -> s.contains("CREATE TABLE IF NOT EXISTS kafka_outbox"));
        assertThat(ddl.getAllValues()).anyMatch(s -> s.contains("idx_kafka_outbox_retry"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendMetricsCollectionEvent_success_noOutboxInsert() throws Exception {
        SendResult<String, MetricsCollectionEvent> sr = org.mockito.Mockito.mock(SendResult.class);
        when(kafkaTemplate.send(eq("metrics-topic"), anyString(), any(MetricsCollectionEvent.class)))
                .thenReturn(CompletableFuture.completedFuture(sr));
        MetricsCollectionEvent ev = MetricsCollectionEvent.builder().taskId("t").testStartTime(1L).testEndTime(2L).build();
        service.sendMetricsCollectionEvent("t", ev);
        verify(jdbcTemplate, org.mockito.Mockito.never()).update(anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendMetricsCollectionEvent_onKafkaFailure_insertsOutbox() throws Exception {
        CompletableFuture<SendResult<String, MetricsCollectionEvent>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("broker-down"));
        when(kafkaTemplate.send(eq("metrics-topic"), anyString(), any(MetricsCollectionEvent.class)))
                .thenReturn(failed);
        MetricsCollectionEvent ev = MetricsCollectionEvent.builder().taskId("t2").testStartTime(1L).testEndTime(2L).build();
        service.sendMetricsCollectionEvent("t2", ev);
        verify(jdbcTemplate).update(anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendMetricsCollectionEvent_onKafkaFailure_usesFallbackPayloadWhenSerializationFails() throws Exception {
        ObjectMapper brokenMapper = mock(ObjectMapper.class);
        when(brokenMapper.writeValueAsString(any(MetricsCollectionEvent.class)))
                .thenThrow(new JsonProcessingException("cannot serialize") {});

        KafkaOutboxService svc = new KafkaOutboxService(jdbcTemplate, brokenMapper, kafkaTemplate);
        ReflectionTestUtils.setField(svc, "metricsCollectionTasksTopic", "metrics-topic");
        ReflectionTestUtils.setField(svc, "retryDelayMs", 1000L);
        ReflectionTestUtils.setField(svc, "batchSize", 10);

        CompletableFuture<SendResult<String, MetricsCollectionEvent>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("broker-down"));
        when(kafkaTemplate.send(eq("metrics-topic"), anyString(), any(MetricsCollectionEvent.class)))
                .thenReturn(failed);

        MetricsCollectionEvent ev = MetricsCollectionEvent.builder().taskId("t3").testStartTime(1L).testEndTime(2L).build();
        svc.sendMetricsCollectionEvent("t3", ev);

        verify(jdbcTemplate).update(
                anyString(),
                any(),
                eq("EXECUTION"),
                eq("METRICS_COLLECTION_EVENT"),
                eq("metrics-topic"),
                eq("t3"),
                eq("{\"error\":\"payload-serialization-failed\"}"),
                eq("PENDING"),
                any(),
                any());
    }

    @Test
    void flushPending_emptyQuery_doesNothing() {
        when(jdbcTemplate.queryForList(
                anyString(),
                eq("EXECUTION"),
                eq("PENDING"),
                eq("METRICS_COLLECTION_EVENT"),
                eq(10)))
                .thenReturn(Collections.emptyList());
        service.flushPending();
        verify(kafkaTemplate, org.mockito.Mockito.never()).send(anyString(), anyString(), any(MetricsCollectionEvent.class));
    }

    @Test
    void flushPending_rowSent_updatesStatus() throws Exception {
        UUID id = UUID.randomUUID();
        MetricsCollectionEvent ev = MetricsCollectionEvent.builder().taskId("k").testStartTime(1L).testEndTime(2L).build();
        String json = new ObjectMapper().writeValueAsString(ev);
        when(jdbcTemplate.queryForList(
                anyString(),
                eq("EXECUTION"),
                eq("PENDING"),
                eq("METRICS_COLLECTION_EVENT"),
                eq(10)))
                .thenReturn(List.of(Map.of("id", id, "event_key", "k", "payload_json", json)));
        SendResult<String, MetricsCollectionEvent> sr = org.mockito.Mockito.mock(SendResult.class);
        when(kafkaTemplate.send(eq("metrics-topic"), eq("k"), any(MetricsCollectionEvent.class)))
                .thenReturn(CompletableFuture.completedFuture(sr));

        service.flushPending();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), eq("SENT"), eq(id));
        assertThat(sql.getValue()).contains("UPDATE kafka_outbox");
    }

    @Test
    void flushPending_onKafkaSendFailure_updatesAttempts() throws Exception {
        UUID id = UUID.randomUUID();
        MetricsCollectionEvent ev = MetricsCollectionEvent.builder().taskId("k2").testStartTime(1L).testEndTime(2L).build();
        String json = new ObjectMapper().writeValueAsString(ev);
        when(jdbcTemplate.queryForList(
                anyString(),
                eq("EXECUTION"),
                eq("PENDING"),
                eq("METRICS_COLLECTION_EVENT"),
                eq(10)))
                .thenReturn(List.of(Map.of("id", id, "event_key", "k2", "payload_json", json)));
        CompletableFuture<SendResult<String, MetricsCollectionEvent>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("send-fail"));
        when(kafkaTemplate.send(eq("metrics-topic"), eq("k2"), any(MetricsCollectionEvent.class)))
                .thenReturn(failed);

        service.flushPending();

        verify(jdbcTemplate).update(
                argThat((String sql) -> sql != null && sql.contains("attempts") && sql.contains("next_attempt_at")),
                any(),
                any(),
                eq(id));
    }

    @Test
    void shrinkError_whenGetMessageNull_usesEmptySuffix() throws Exception {
        Method m = KafkaOutboxService.class.getDeclaredMethod("shrinkError", Exception.class);
        m.setAccessible(true);
        String out = (String) m.invoke(null, new RuntimeException((String) null));
        assertThat(out).isEqualTo("RuntimeException: ");
    }

    @Test
    void shrinkError_truncatesWhenLongerThan2000() throws Exception {
        Method m = KafkaOutboxService.class.getDeclaredMethod("shrinkError", Exception.class);
        m.setAccessible(true);
        String detail = "x".repeat(2100);
        String out = (String) m.invoke(null, new RuntimeException(detail));
        String full = "RuntimeException: " + detail;
        assertThat(out).hasSize(2000).isEqualTo(full.substring(0, 2000));
    }
}
