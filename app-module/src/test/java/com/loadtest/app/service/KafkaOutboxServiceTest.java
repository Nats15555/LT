package com.loadtest.app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadtest.app.dto.SummarizationTaskEvent;
import com.loadtest.app.dto.TestTaskEvent;
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
    private final ObjectMapper objectMapper = new ObjectMapper();
    @Mock
    private KafkaTemplate<String, TestTaskEvent> testTaskKafkaTemplate;
    @Mock
    private KafkaTemplate<String, SummarizationTaskEvent> summarizationKafkaTemplate;

    private KafkaOutboxService service;

    @BeforeEach
    void setUp() {
        service = new KafkaOutboxService(jdbcTemplate, objectMapper, testTaskKafkaTemplate, summarizationKafkaTemplate);
        ReflectionTestUtils.setField(service, "testTasksTopic", "tt");
        ReflectionTestUtils.setField(service, "summarizationTasksTopic", "st");
        ReflectionTestUtils.setField(service, "retryDelayMs", 5L);
        ReflectionTestUtils.setField(service, "batchSize", 10);
    }

    @Test
    void ensureTable_createsIndexes() {
        service.ensureTable();
        verify(jdbcTemplate, times(2)).execute(anyString());
    }

    @Test
    void sendTestTaskEvent_successDoesNotWriteOutbox() throws Exception {
        @SuppressWarnings("unchecked")
        CompletableFuture<SendResult<String, TestTaskEvent>> future =
                CompletableFuture.completedFuture(org.mockito.Mockito.mock(SendResult.class));
        when(testTaskKafkaTemplate.send(eq("tt"), anyString(), any(TestTaskEvent.class))).thenReturn(future);
        service.sendTestTaskEvent("t1", TestTaskEvent.builder().taskId("t1").build());
        verify(jdbcTemplate, never()).update(contains("INSERT INTO kafka_outbox"), any(), any(), any(), any(), any(),
                any(), any(), any(), any());
    }

    @Test
    void sendTestTaskEvent_failurePersistsOutbox() {
        when(testTaskKafkaTemplate.send(eq("tt"), anyString(), any(TestTaskEvent.class)))
                .thenThrow(new RuntimeException("broker down"));
        service.sendTestTaskEvent("t1", TestTaskEvent.builder().taskId("t1").build());
        verify(jdbcTemplate).update(contains("INSERT INTO kafka_outbox"), any(), any(), any(), any(), any(), any(),
                any(), any(), any());
    }

    @Test
    void sendSummarizationTaskEvent_failurePersistsOutbox() {
        when(summarizationKafkaTemplate.send(eq("st"), anyString(), any(SummarizationTaskEvent.class)))
                .thenThrow(new RuntimeException("broker down"));
        service.sendSummarizationTaskEvent("t1", new SummarizationTaskEvent("t1", "sum"));
        verify(jdbcTemplate).update(contains("INSERT INTO kafka_outbox"), any(), any(), any(), any(), any(), any(),
                any(), any(), any());
    }

    @Test
    void sendSummarizationTaskEvent_successDoesNotWriteOutbox() {
        @SuppressWarnings("unchecked")
        CompletableFuture<SendResult<String, SummarizationTaskEvent>> future =
                CompletableFuture.completedFuture(org.mockito.Mockito.mock(SendResult.class));
        when(summarizationKafkaTemplate.send(eq("st"), anyString(), any(SummarizationTaskEvent.class))).thenReturn(future);

        service.sendSummarizationTaskEvent("s1", new SummarizationTaskEvent("s1", "sum"));

        verify(jdbcTemplate, never()).update(contains("INSERT INTO kafka_outbox"), any(), any(), any(), any(), any(),
                any(), any(), any(), any());
    }

    @Test
    void flushPending_marksSentForTestTaskEvent() throws Exception {
        UUID outboxId = UUID.randomUUID();
        TestTaskEvent evt = TestTaskEvent.builder().taskId("tid").build();
        doReturn(List.of(
                Map.of(
                        "id", outboxId,
                        "event_type", "TEST_TASK_EVENT",
                        "topic", "tt",
                        "event_key", "tid",
                        "payload_json", objectMapper.writeValueAsString(evt),
                        "attempts", 0)))
                .when(jdbcTemplate)
                .queryForList(contains("FROM kafka_outbox"), eq("APP"), eq("PENDING"), eq(10));
        @SuppressWarnings("unchecked")
        CompletableFuture<SendResult<String, TestTaskEvent>> future =
                CompletableFuture.completedFuture(org.mockito.Mockito.mock(SendResult.class));
        when(testTaskKafkaTemplate.send(eq("tt"), eq("tid"), any(TestTaskEvent.class))).thenReturn(future);

        service.flushPending();

        verify(jdbcTemplate).update(contains("UPDATE kafka_outbox SET status"), eq("SENT"), eq(outboxId));
    }

    @Test
    void flushPending_unknownEventType_updatesRetry() {
        UUID outboxId = UUID.randomUUID();
        doReturn(List.of(
                Map.of(
                        "id", outboxId,
                        "event_type", "OTHER",
                        "topic", "tt",
                        "event_key", "k",
                        "payload_json", "{}",
                        "attempts", 0)))
                .when(jdbcTemplate)
                .queryForList(contains("FROM kafka_outbox"), eq("APP"), eq("PENDING"), eq(10));

        service.flushPending();

        verify(jdbcTemplate).update(contains("attempts = attempts + 1"), any(), any(), eq(outboxId));
    }

    @Test
    void flushPending_invalidPayload_updatesRetry() {
        UUID outboxId = UUID.randomUUID();
        doReturn(List.of(
                Map.of(
                        "id", outboxId,
                        "event_type", "TEST_TASK_EVENT",
                        "topic", "tt",
                        "event_key", "k",
                        "payload_json", "{invalid-json",
                        "attempts", 0)))
                .when(jdbcTemplate)
                .queryForList(contains("FROM kafka_outbox"), eq("APP"), eq("PENDING"), eq(10));

        service.flushPending();

        verify(jdbcTemplate).update(contains("attempts = attempts + 1"), any(), any(), eq(outboxId));
    }

    @Test
    void flushPending_summarizationEventUsesSummarizationTemplate() throws Exception {
        UUID outboxId = UUID.randomUUID();
        SummarizationTaskEvent evt = new SummarizationTaskEvent("tid", "sum");
        doReturn(List.of(
                Map.of(
                        "id", outboxId,
                        "event_type", "SUMMARIZATION_TASK_EVENT",
                        "topic", "st",
                        "event_key", "tid",
                        "payload_json", objectMapper.writeValueAsString(evt),
                        "attempts", 0)))
                .when(jdbcTemplate)
                .queryForList(contains("FROM kafka_outbox"), eq("APP"), eq("PENDING"), eq(10));
        @SuppressWarnings("unchecked")
        CompletableFuture<SendResult<String, SummarizationTaskEvent>> future =
                CompletableFuture.completedFuture(org.mockito.Mockito.mock(SendResult.class));
        when(summarizationKafkaTemplate.send(eq("st"), eq("tid"), any(SummarizationTaskEvent.class))).thenReturn(future);

        service.flushPending();

        verify(summarizationKafkaTemplate).send(eq("st"), eq("tid"), any(SummarizationTaskEvent.class));
        verify(jdbcTemplate).update(contains("UPDATE kafka_outbox SET status"), eq("SENT"), eq(outboxId));
    }

    @Test
    void flushPending_sendFailureAfterParse_updatesRetry() throws Exception {
        UUID outboxId = UUID.randomUUID();
        TestTaskEvent evt = TestTaskEvent.builder().taskId("tid").build();
        doReturn(List.of(
                Map.of(
                        "id", outboxId,
                        "event_type", "TEST_TASK_EVENT",
                        "topic", "tt",
                        "event_key", "tid",
                        "payload_json", objectMapper.writeValueAsString(evt),
                        "attempts", 0)))
                .when(jdbcTemplate)
                .queryForList(contains("FROM kafka_outbox"), eq("APP"), eq("PENDING"), eq(10));
        when(testTaskKafkaTemplate.send(eq("tt"), eq("tid"), any(TestTaskEvent.class)))
                .thenThrow(new RuntimeException("send failed"));

        service.flushPending();

        verify(jdbcTemplate).update(contains("attempts = attempts + 1"), any(), any(), eq(outboxId));
    }

    @Test
    void flushPending_withNoRows_doesNothing() {
        doReturn(List.of())
                .when(jdbcTemplate)
                .queryForList(contains("FROM kafka_outbox"), eq("APP"), eq("PENDING"), eq(10));

        service.flushPending();

        verify(testTaskKafkaTemplate, never()).send(anyString(), anyString(), any(TestTaskEvent.class));
        verify(summarizationKafkaTemplate, never()).send(anyString(), anyString(), any(SummarizationTaskEvent.class));
    }

    @Test
    void sendTestTaskEvent_payloadSerializationFailure_usesFallbackJson() {
        ObjectMapper badOm = org.mockito.Mockito.mock(ObjectMapper.class);
        KafkaOutboxService s2 = new KafkaOutboxService(jdbcTemplate, badOm, testTaskKafkaTemplate, summarizationKafkaTemplate);
        ReflectionTestUtils.setField(s2, "testTasksTopic", "tt");
        ReflectionTestUtils.setField(s2, "summarizationTasksTopic", "st");
        when(testTaskKafkaTemplate.send(eq("tt"), anyString(), any(TestTaskEvent.class)))
                .thenThrow(new RuntimeException("broker down"));
        try {
            when(badOm.writeValueAsString(any())).thenThrow(new RuntimeException("ser"));
        } catch (Exception ignored) {
        }

        s2.sendTestTaskEvent("t-ser", TestTaskEvent.builder().taskId("t-ser").build());

        verify(jdbcTemplate).update(
                contains("INSERT INTO kafka_outbox"),
                any(),
                any(),
                any(),
                any(),
                any(),
                eq("{\"error\":\"payload-serialization-failed\"}"),
                any(),
                any(),
                any()
        );
    }

    @Test
    void shrinkError_handlesNullAndLongMessage() {
        Exception withNull = new RuntimeException((String) null);
        String m1 = ReflectionTestUtils.invokeMethod(service, "shrinkError", withNull);
        org.assertj.core.api.Assertions.assertThat(m1).isEqualTo("RuntimeException: ");

        String longMsg = "x".repeat(2500);
        Exception longEx = new RuntimeException(longMsg);
        String m2 = ReflectionTestUtils.invokeMethod(service, "shrinkError", longEx);
        org.assertj.core.api.Assertions.assertThat(m2.length()).isEqualTo(2000);
    }
}
