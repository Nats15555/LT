package com.loadtest.metrics.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadtest.metrics.dto.SummarizationTaskEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KafkaOutboxServiceTest {

    @Mock
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    @Mock
    private KafkaTemplate<String, SummarizationTaskEvent> kafkaTemplate;

    private KafkaOutboxService service;

    @BeforeEach
    void setUp() {
        service = new KafkaOutboxService(jdbcTemplate, new ObjectMapper(), kafkaTemplate);
        ReflectionTestUtils.setField(service, "summarizationTasksTopic", "st");
        ReflectionTestUtils.setField(service, "retryDelayMs", 5L);
        ReflectionTestUtils.setField(service, "batchSize", 10);
    }

    @Test
    void ensureTable_createsSchema() {
        service.ensureTable();
        verify(jdbcTemplate, times(2)).execute(anyString());
    }

    @Test
    void sendSummarizationEvent_successAndFailure() {
        @SuppressWarnings("unchecked")
        CompletableFuture<SendResult<String, SummarizationTaskEvent>> ok =
                CompletableFuture.completedFuture(org.mockito.Mockito.mock(SendResult.class));
        when(kafkaTemplate.send(eq("st"), eq("k1"), any(SummarizationTaskEvent.class))).thenReturn(ok);
        service.sendSummarizationEvent("k1", new SummarizationTaskEvent("k1", "s"));
        verify(jdbcTemplate, never()).update(contains("INSERT INTO kafka_outbox"), any(), any(), any(), any(), any(), any(), any(), any(), any());

        when(kafkaTemplate.send(eq("st"), eq("k2"), any(SummarizationTaskEvent.class))).thenThrow(new RuntimeException("down"));
        service.sendSummarizationEvent("k2", new SummarizationTaskEvent("k2", "s"));
        verify(jdbcTemplate).update(contains("INSERT INTO kafka_outbox"), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void flushPending_branches() throws Exception {
        UUID id = UUID.randomUUID();
        SummarizationTaskEvent ev = new SummarizationTaskEvent("k", "s");
        doReturn(List.of(Map.of("id", id, "event_key", "k", "payload_json", new ObjectMapper().writeValueAsString(ev))))
                .when(jdbcTemplate).queryForList(contains("FROM kafka_outbox"), eq("METRICS_COLLECTOR"), eq("PENDING"), eq("SUMMARIZATION_TASK_EVENT"), eq(10));
        @SuppressWarnings("unchecked")
        CompletableFuture<SendResult<String, SummarizationTaskEvent>> ok =
                CompletableFuture.completedFuture(org.mockito.Mockito.mock(SendResult.class));
        when(kafkaTemplate.send(eq("st"), eq("k"), any(SummarizationTaskEvent.class))).thenReturn(ok);

        service.flushPending();
        verify(jdbcTemplate).update(contains("SET status = ?"), eq("SENT"), eq(id));

        doReturn(List.of(Map.of("id", id, "event_key", "k", "payload_json", "{bad")))
                .when(jdbcTemplate).queryForList(contains("FROM kafka_outbox"), eq("METRICS_COLLECTOR"), eq("PENDING"), eq("SUMMARIZATION_TASK_EVENT"), eq(10));
        service.flushPending();
        verify(jdbcTemplate).update(contains("attempts = attempts + 1"), any(), any(), eq(id));
    }

    @Test
    void flushPending_withNoRows_doesNothing() {
        doReturn(List.of())
                .when(jdbcTemplate).queryForList(contains("FROM kafka_outbox"), eq("METRICS_COLLECTOR"), eq("PENDING"), eq("SUMMARIZATION_TASK_EVENT"), eq(10));
        service.flushPending();
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any(SummarizationTaskEvent.class));
    }

    @Test
    void sendSummarizationEvent_payloadSerializationFallback() {
        ObjectMapper bad = org.mockito.Mockito.mock(ObjectMapper.class);
        KafkaOutboxService s2 = new KafkaOutboxService(jdbcTemplate, bad, kafkaTemplate);
        ReflectionTestUtils.setField(s2, "summarizationTasksTopic", "st");
        ReflectionTestUtils.setField(s2, "retryDelayMs", 5L);
        when(kafkaTemplate.send(eq("st"), eq("k3"), any(SummarizationTaskEvent.class))).thenThrow(new RuntimeException("down"));
        try {
            when(bad.writeValueAsString(any())).thenThrow(new RuntimeException("ser"));
        } catch (Exception ignored) {
        }
        s2.sendSummarizationEvent("k3", new SummarizationTaskEvent("k3", "s"));
        verify(jdbcTemplate).update(contains("INSERT INTO kafka_outbox"), any(), any(), any(), any(), any(),
                eq("{\"error\":\"payload-serialization-failed\"}"), any(), any(), any());
    }

    @Test
    void shrinkError_handlesNullAndLongMessage_branches125_126() {
        Exception nullMsg = new IllegalStateException((String) null);
        String s1 = (String) ReflectionTestUtils.invokeMethod(KafkaOutboxService.class, "shrinkError", nullMsg);
        assertThat(s1).isEqualTo("IllegalStateException: ");

        String longMsg = "x".repeat(3000);
        Exception longEx = new RuntimeException(longMsg);
        String s2 = (String) ReflectionTestUtils.invokeMethod(KafkaOutboxService.class, "shrinkError", longEx);
        assertThat(s2).hasSize(2000);
        assertThat(s2).startsWith("RuntimeException: ");
    }
}

