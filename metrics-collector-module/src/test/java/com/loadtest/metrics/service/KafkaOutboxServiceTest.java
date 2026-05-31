package com.loadtest.metrics.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadtest.metrics.dto.SummarizationTaskEvent;
import com.loadtest.metrics.persistence.KafkaOutboxEntity;
import com.loadtest.metrics.persistence.KafkaOutboxRepository;
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

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KafkaOutboxServiceTest {

    @Mock
    private KafkaOutboxRepository kafkaOutboxRepository;
    @Mock
    private KafkaTemplate<String, SummarizationTaskEvent> kafkaTemplate;

    private KafkaOutboxService service;

    @BeforeEach
    void setUp() {
        service = new KafkaOutboxService(kafkaOutboxRepository, new ObjectMapper(), kafkaTemplate);
        ReflectionTestUtils.setField(service, "summarizationTasksTopic", "st");
        ReflectionTestUtils.setField(service, "retryDelayMs", 5L);
        ReflectionTestUtils.setField(service, "batchSize", 10);
    }

    @Test
    void ensureSchema_createsTableAndIndex() {
        service.ensureSchema();
        verify(kafkaOutboxRepository).ensureTable();
        verify(kafkaOutboxRepository).ensureRetryIndex();
    }

    @Test
    void sendSummarizationEvent_successAndFailure() {
        @SuppressWarnings("unchecked")
        CompletableFuture<SendResult<String, SummarizationTaskEvent>> ok =
                CompletableFuture.completedFuture(org.mockito.Mockito.mock(SendResult.class));
        when(kafkaTemplate.send(eq("st"), eq("k1"), any(SummarizationTaskEvent.class))).thenReturn(ok);
        service.sendSummarizationEvent("k1", new SummarizationTaskEvent("k1", "s"));
        verify(kafkaOutboxRepository, never()).save(any());

        CompletableFuture<SendResult<String, SummarizationTaskEvent>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("down"));
        when(kafkaTemplate.send(eq("st"), eq("k2"), any(SummarizationTaskEvent.class))).thenReturn(failed);
        service.sendSummarizationEvent("k2", new SummarizationTaskEvent("k2", "s"));
        verify(kafkaOutboxRepository).save(any(KafkaOutboxEntity.class));
    }

    @Test
    void flushPending_branches() throws Exception {
        UUID id = UUID.randomUUID();
        SummarizationTaskEvent ev = new SummarizationTaskEvent("k", "s");
        KafkaOutboxEntity row = outboxRow(id, new ObjectMapper().writeValueAsString(ev));
        doReturn(List.of(row))
                .when(kafkaOutboxRepository)
                .findByModuleAndStatusAndEventTypeAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                        eq("METRICS_COLLECTOR"), eq("PENDING"), eq("SUMMARIZATION_TASK_EVENT"),
                        any(OffsetDateTime.class), any(Pageable.class));
        @SuppressWarnings("unchecked")
        CompletableFuture<SendResult<String, SummarizationTaskEvent>> ok =
                CompletableFuture.completedFuture(org.mockito.Mockito.mock(SendResult.class));
        when(kafkaTemplate.send(eq("st"), eq("k"), any(SummarizationTaskEvent.class))).thenReturn(ok);

        service.flushPending();
        assertThat(row.getStatus()).isEqualTo("SENT");

        KafkaOutboxEntity badRow = outboxRow(id, "{bad");
        doReturn(List.of(badRow))
                .when(kafkaOutboxRepository)
                .findByModuleAndStatusAndEventTypeAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                        eq("METRICS_COLLECTOR"), eq("PENDING"), eq("SUMMARIZATION_TASK_EVENT"),
                        any(OffsetDateTime.class), any(Pageable.class));
        service.flushPending();
        assertThat(badRow.getAttempts()).isEqualTo(1);
    }

    @Test
    void flushPending_withNoRows_doesNothing() {
        doReturn(List.of())
                .when(kafkaOutboxRepository)
                .findByModuleAndStatusAndEventTypeAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                        eq("METRICS_COLLECTOR"), eq("PENDING"), eq("SUMMARIZATION_TASK_EVENT"),
                        any(OffsetDateTime.class), any(Pageable.class));
        service.flushPending();
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any(SummarizationTaskEvent.class));
    }

    @Test
    void sendSummarizationEvent_payloadSerializationFallback() throws JsonProcessingException {
        ObjectMapper bad = org.mockito.Mockito.mock(ObjectMapper.class);
        KafkaOutboxService s2 = new KafkaOutboxService(kafkaOutboxRepository, bad, kafkaTemplate);
        ReflectionTestUtils.setField(s2, "summarizationTasksTopic", "st");
        ReflectionTestUtils.setField(s2, "retryDelayMs", 5L);
        CompletableFuture<SendResult<String, SummarizationTaskEvent>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("down"));
        when(kafkaTemplate.send(eq("st"), eq("k3"), any(SummarizationTaskEvent.class))).thenReturn(failed);
        doThrow(new JsonProcessingException("ser") {}).when(bad).writeValueAsString(any());
        s2.sendSummarizationEvent("k3", new SummarizationTaskEvent("k3", "s"));

        ArgumentCaptor<KafkaOutboxEntity> captor = ArgumentCaptor.forClass(KafkaOutboxEntity.class);
        verify(kafkaOutboxRepository).save(captor.capture());
        assertThat(captor.getValue().getPayloadJson()).isEqualTo("{\"error\":\"payload-serialization-failed\"}");
    }

    @Test
    void shrinkError_handlesNullAndLongMessage() {
        Exception nullMsg = new IllegalStateException((String) null);
        String s1 = (String) ReflectionTestUtils.invokeMethod(KafkaOutboxService.class, "shrinkError", nullMsg);
        assertThat(s1).isEqualTo("IllegalStateException: ");

        String longMsg = "x".repeat(3000);
        KafkaOutboxPublishException longEx = new KafkaOutboxPublishException(longMsg, null);
        String s2 = (String) ReflectionTestUtils.invokeMethod(KafkaOutboxService.class, "shrinkError", longEx);
        assertThat(s2).hasSize(2000);
        assertThat(s2).startsWith("KafkaOutboxPublishException: ");
    }

    private static KafkaOutboxEntity outboxRow(UUID id, String payloadJson) {
        OffsetDateTime now = OffsetDateTime.now();
        return KafkaOutboxEntity.builder()
                .id(id)
                .module("METRICS_COLLECTOR")
                .eventType("SUMMARIZATION_TASK_EVENT")
                .topic("st")
                .eventKey("k")
                .payloadJson(payloadJson)
                .status("PENDING")
                .attempts(0)
                .nextAttemptAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}
