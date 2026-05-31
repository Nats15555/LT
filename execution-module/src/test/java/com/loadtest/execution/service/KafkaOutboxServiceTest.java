package com.loadtest.execution.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadtest.execution.dto.MetricsCollectionEvent;
import com.loadtest.execution.persistence.KafkaOutboxEntity;
import com.loadtest.execution.persistence.KafkaOutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KafkaOutboxServiceTest {

    @Mock private KafkaOutboxRepository kafkaOutboxRepository;
    @Mock private KafkaTemplate<String, MetricsCollectionEvent> kafkaTemplate;

    private KafkaOutboxService service;

    @BeforeEach
    void setUp() {
        reset(kafkaOutboxRepository, kafkaTemplate);
        service = new KafkaOutboxService(kafkaOutboxRepository, new ObjectMapper(), kafkaTemplate);
        ReflectionTestUtils.setField(service, "metricsCollectionTasksTopic", "metrics-topic");
        ReflectionTestUtils.setField(service, "retryDelayMs", 1000L);
        ReflectionTestUtils.setField(service, "batchSize", 10);
    }

    @Test
    void ensureSchema_runsDDL() {
        service.ensureSchema();
        verify(kafkaOutboxRepository).ensureTable();
        verify(kafkaOutboxRepository).ensureRetryIndex();
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendMetricsCollectionEvent_success_noOutboxInsert() throws Exception {
        SendResult<String, MetricsCollectionEvent> sr = org.mockito.Mockito.mock(SendResult.class);
        when(kafkaTemplate.send(eq("metrics-topic"), anyString(), any(MetricsCollectionEvent.class)))
                .thenReturn(CompletableFuture.completedFuture(sr));
        MetricsCollectionEvent ev = new MetricsCollectionEvent("t", 1L, 2L);
        service.sendMetricsCollectionEvent("t", ev);
        verify(kafkaOutboxRepository, never()).save(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendMetricsCollectionEvent_onKafkaFailure_insertsOutbox() throws Exception {
        CompletableFuture<SendResult<String, MetricsCollectionEvent>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("broker-down"));
        when(kafkaTemplate.send(eq("metrics-topic"), anyString(), any(MetricsCollectionEvent.class)))
                .thenReturn(failed);
        MetricsCollectionEvent ev = new MetricsCollectionEvent("t2", 1L, 2L);
        service.sendMetricsCollectionEvent("t2", ev);
        verify(kafkaOutboxRepository).save(any(KafkaOutboxEntity.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendMetricsCollectionEvent_onKafkaFailure_usesFallbackPayloadWhenSerializationFails() throws Exception {
        ObjectMapper brokenMapper = mock(ObjectMapper.class);
        when(brokenMapper.writeValueAsString(any(MetricsCollectionEvent.class)))
                .thenThrow(new JsonProcessingException("cannot serialize") {});

        KafkaOutboxService svc = new KafkaOutboxService(kafkaOutboxRepository, brokenMapper, kafkaTemplate);
        ReflectionTestUtils.setField(svc, "metricsCollectionTasksTopic", "metrics-topic");
        ReflectionTestUtils.setField(svc, "retryDelayMs", 1000L);
        ReflectionTestUtils.setField(svc, "batchSize", 10);

        CompletableFuture<SendResult<String, MetricsCollectionEvent>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("broker-down"));
        when(kafkaTemplate.send(eq("metrics-topic"), anyString(), any(MetricsCollectionEvent.class)))
                .thenReturn(failed);

        svc.sendMetricsCollectionEvent("t3", new MetricsCollectionEvent("t3", 1L, 2L));

        ArgumentCaptor<KafkaOutboxEntity> captor = ArgumentCaptor.forClass(KafkaOutboxEntity.class);
        verify(kafkaOutboxRepository).save(captor.capture());
        assertThat(captor.getValue().getPayloadJson()).isEqualTo("{\"error\":\"payload-serialization-failed\"}");
    }

    @Test
    void flushPending_emptyQuery_doesNothing() {
        when(kafkaOutboxRepository.findByModuleAndStatusAndEventTypeAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("EXECUTION"),
                eq("PENDING"),
                eq("METRICS_COLLECTION_EVENT"),
                any(OffsetDateTime.class),
                any(Pageable.class)))
                .thenReturn(Collections.emptyList());
        service.flushPending();
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any(MetricsCollectionEvent.class));
    }

    @Test
    void flushPending_rowSent_updatesStatus() throws Exception {
        MetricsCollectionEvent ev = new MetricsCollectionEvent("k", 1L, 2L);
        KafkaOutboxEntity row = outboxRow("k", new ObjectMapper().writeValueAsString(ev));
        when(kafkaOutboxRepository.findByModuleAndStatusAndEventTypeAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("EXECUTION"),
                eq("PENDING"),
                eq("METRICS_COLLECTION_EVENT"),
                any(OffsetDateTime.class),
                any(Pageable.class)))
                .thenReturn(List.of(row));
        SendResult<String, MetricsCollectionEvent> sr = org.mockito.Mockito.mock(SendResult.class);
        when(kafkaTemplate.send(eq("metrics-topic"), eq("k"), any(MetricsCollectionEvent.class)))
                .thenReturn(CompletableFuture.completedFuture(sr));

        service.flushPending();

        assertThat(row.getStatus()).isEqualTo("SENT");
    }

    @Test
    void flushPending_onKafkaSendFailure_updatesAttempts() throws Exception {
        MetricsCollectionEvent ev = new MetricsCollectionEvent("k2", 1L, 2L);
        KafkaOutboxEntity row = outboxRow("k2", new ObjectMapper().writeValueAsString(ev));
        when(kafkaOutboxRepository.findByModuleAndStatusAndEventTypeAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("EXECUTION"),
                eq("PENDING"),
                eq("METRICS_COLLECTION_EVENT"),
                any(OffsetDateTime.class),
                any(Pageable.class)))
                .thenReturn(List.of(row));
        CompletableFuture<SendResult<String, MetricsCollectionEvent>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("send-fail"));
        when(kafkaTemplate.send(eq("metrics-topic"), eq("k2"), any(MetricsCollectionEvent.class)))
                .thenReturn(failed);

        service.flushPending();

        assertThat(row.getAttempts()).isEqualTo(1);
        assertThat(row.getLastError()).isNotNull();
    }

    @Test
    void shrinkError_whenGetMessageNull_usesEmptySuffix() throws Exception {
        Method m = KafkaOutboxService.class.getDeclaredMethod("shrinkError", Exception.class);
        m.setAccessible(true);
        String out = (String) m.invoke(null, new KafkaOutboxPublishException("Kafka publish failed", null));
        assertThat(out).isEqualTo("KafkaOutboxPublishException: Kafka publish failed");
    }

    @Test
    void shrinkError_truncatesWhenLongerThan2000() throws Exception {
        Method m = KafkaOutboxService.class.getDeclaredMethod("shrinkError", Exception.class);
        m.setAccessible(true);
        String detail = "x".repeat(2100);
        String out = (String) m.invoke(null, new KafkaOutboxPublishException(detail, null));
        String full = "KafkaOutboxPublishException: " + detail;
        assertThat(out).hasSize(2000).isEqualTo(full.substring(0, 2000));
    }

    private static KafkaOutboxEntity outboxRow(String key, String payloadJson) {
        OffsetDateTime now = OffsetDateTime.now();
        return KafkaOutboxEntity.builder()
                .id(UUID.randomUUID())
                .module("EXECUTION")
                .eventType("METRICS_COLLECTION_EVENT")
                .topic("metrics-topic")
                .eventKey(key)
                .payloadJson(payloadJson)
                .status("PENDING")
                .attempts(0)
                .nextAttemptAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}
