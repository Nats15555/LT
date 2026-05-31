package com.loadtest.app.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadtest.app.dto.SummarizationTaskEvent;
import com.loadtest.app.dto.TestTaskEvent;
import com.loadtest.app.persistence.KafkaOutboxEntity;
import com.loadtest.app.persistence.KafkaOutboxRepository;
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
import static com.loadtest.app.testsupport.JsonTestSupport.stubWriteValueAsStringFailure;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KafkaOutboxServiceTest {

    @Mock
    private KafkaOutboxRepository kafkaOutboxRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    @Mock
    private KafkaTemplate<String, TestTaskEvent> testTaskKafkaTemplate;
    @Mock
    private KafkaTemplate<String, SummarizationTaskEvent> summarizationKafkaTemplate;

    private KafkaOutboxService service;

    @BeforeEach
    void setUp() {
        service = new KafkaOutboxService(kafkaOutboxRepository, objectMapper, testTaskKafkaTemplate, summarizationKafkaTemplate);
        ReflectionTestUtils.setField(service, "testTasksTopic", "tt");
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
    void sendTestTaskEvent_successDoesNotWriteOutbox() {
        @SuppressWarnings("unchecked")
        CompletableFuture<SendResult<String, TestTaskEvent>> future =
                CompletableFuture.completedFuture(org.mockito.Mockito.mock(SendResult.class));
        when(testTaskKafkaTemplate.send(eq("tt"), anyString(), any(TestTaskEvent.class))).thenReturn(future);
        service.sendTestTaskEvent("t1", new TestTaskEvent("t1"));
        verify(kafkaOutboxRepository, never()).save(any());
    }

    @Test
    void sendTestTaskEvent_failurePersistsOutbox() {
        CompletableFuture<SendResult<String, TestTaskEvent>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("broker down"));
        when(testTaskKafkaTemplate.send(eq("tt"), anyString(), any(TestTaskEvent.class))).thenReturn(failed);
        service.sendTestTaskEvent("t1", new TestTaskEvent("t1"));
        verify(kafkaOutboxRepository).save(any(KafkaOutboxEntity.class));
    }

    @Test
    void sendSummarizationTaskEvent_failurePersistsOutbox() {
        CompletableFuture<SendResult<String, SummarizationTaskEvent>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("broker down"));
        when(summarizationKafkaTemplate.send(eq("st"), anyString(), any(SummarizationTaskEvent.class))).thenReturn(failed);
        service.sendSummarizationTaskEvent("t1", new SummarizationTaskEvent("t1", "sum"));
        verify(kafkaOutboxRepository).save(any(KafkaOutboxEntity.class));
    }

    @Test
    void sendSummarizationTaskEvent_successDoesNotWriteOutbox() {
        @SuppressWarnings("unchecked")
        CompletableFuture<SendResult<String, SummarizationTaskEvent>> future =
                CompletableFuture.completedFuture(org.mockito.Mockito.mock(SendResult.class));
        when(summarizationKafkaTemplate.send(eq("st"), anyString(), any(SummarizationTaskEvent.class))).thenReturn(future);

        service.sendSummarizationTaskEvent("s1", new SummarizationTaskEvent("s1", "sum"));

        verify(kafkaOutboxRepository, never()).save(any());
    }

    @Test
    void flushPending_marksSentForTestTaskEvent() {
        KafkaOutboxEntity row = outboxRow("TEST_TASK_EVENT", "tt", "tid", "{\"taskId\":\"tid\"}");
        when(kafkaOutboxRepository.findByModuleAndStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("APP"), eq("PENDING"), any(OffsetDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(row));
        @SuppressWarnings("unchecked")
        CompletableFuture<SendResult<String, TestTaskEvent>> future =
                CompletableFuture.completedFuture(org.mockito.Mockito.mock(SendResult.class));
        when(testTaskKafkaTemplate.send(eq("tt"), eq("tid"), any(TestTaskEvent.class))).thenReturn(future);

        service.flushPending();

        assertThat(row.getStatus()).isEqualTo("SENT");
        assertThat(row.getLastError()).isNull();
    }

    @Test
    void flushPending_unknownEventType_updatesRetry() {
        KafkaOutboxEntity row = outboxRow("OTHER", "tt", "k", "{}");
        when(kafkaOutboxRepository.findByModuleAndStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("APP"), eq("PENDING"), any(OffsetDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(row));

        service.flushPending();

        assertThat(row.getAttempts()).isEqualTo(1);
        assertThat(row.getLastError()).isNotNull();
    }

    @Test
    void flushPending_invalidPayload_updatesRetry() {
        KafkaOutboxEntity row = outboxRow("TEST_TASK_EVENT", "tt", "k", "{invalid-json");
        when(kafkaOutboxRepository.findByModuleAndStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("APP"), eq("PENDING"), any(OffsetDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(row));

        service.flushPending();

        assertThat(row.getAttempts()).isEqualTo(1);
    }

    @Test
    void flushPending_summarizationEventUsesSummarizationTemplate() {
        KafkaOutboxEntity row = outboxRow(
                "SUMMARIZATION_TASK_EVENT", "st", "tid",
                "{\"taskId\":\"tid\",\"summarizerName\":\"sum\",\"customPrompt\":null}");
        when(kafkaOutboxRepository.findByModuleAndStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("APP"), eq("PENDING"), any(OffsetDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(row));
        @SuppressWarnings("unchecked")
        CompletableFuture<SendResult<String, SummarizationTaskEvent>> future =
                CompletableFuture.completedFuture(org.mockito.Mockito.mock(SendResult.class));
        when(summarizationKafkaTemplate.send(eq("st"), eq("tid"), any(SummarizationTaskEvent.class))).thenReturn(future);

        service.flushPending();

        verify(summarizationKafkaTemplate).send(eq("st"), eq("tid"), any(SummarizationTaskEvent.class));
        assertThat(row.getStatus()).isEqualTo("SENT");
    }

    @Test
    void flushPending_sendFailureAfterParse_updatesRetry() {
        KafkaOutboxEntity row = outboxRow("TEST_TASK_EVENT", "tt", "tid", "{\"taskId\":\"tid\"}");
        when(kafkaOutboxRepository.findByModuleAndStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("APP"), eq("PENDING"), any(OffsetDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(row));
        CompletableFuture<SendResult<String, TestTaskEvent>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("send failed"));
        when(testTaskKafkaTemplate.send(eq("tt"), eq("tid"), any(TestTaskEvent.class))).thenReturn(failed);

        service.flushPending();

        assertThat(row.getAttempts()).isEqualTo(1);
    }

    @Test
    void flushPending_withNoRows_doesNothing() {
        when(kafkaOutboxRepository.findByModuleAndStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("APP"), eq("PENDING"), any(OffsetDateTime.class), any(Pageable.class)))
                .thenReturn(List.of());

        service.flushPending();

        verify(testTaskKafkaTemplate, never()).send(anyString(), anyString(), any(TestTaskEvent.class));
        verify(summarizationKafkaTemplate, never()).send(anyString(), anyString(), any(SummarizationTaskEvent.class));
    }

    @Test
    void sendTestTaskEvent_payloadSerializationFailure_usesFallbackJson() {
        ObjectMapper badOm = org.mockito.Mockito.mock(ObjectMapper.class);
        KafkaOutboxService s2 = new KafkaOutboxService(kafkaOutboxRepository, badOm, testTaskKafkaTemplate, summarizationKafkaTemplate);
        ReflectionTestUtils.setField(s2, "testTasksTopic", "tt");
        ReflectionTestUtils.setField(s2, "summarizationTasksTopic", "st");
        CompletableFuture<SendResult<String, TestTaskEvent>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("broker down"));
        when(testTaskKafkaTemplate.send(eq("tt"), anyString(), any(TestTaskEvent.class))).thenReturn(failed);
        stubWriteValueAsStringFailure(badOm, new JsonProcessingException("ser") {});

        s2.sendTestTaskEvent("t-ser", new TestTaskEvent("t-ser"));

        ArgumentCaptor<KafkaOutboxEntity> captor = ArgumentCaptor.forClass(KafkaOutboxEntity.class);
        verify(kafkaOutboxRepository).save(captor.capture());
        assertThat(captor.getValue().getPayloadJson()).isEqualTo("{\"error\":\"payload-serialization-failed\"}");
    }

    @Test
    void shrinkError_handlesNullAndLongMessage() {
        KafkaOutboxPublishException withNull = new KafkaOutboxPublishException("Kafka publish failed", null);
        String m1 = ReflectionTestUtils.invokeMethod(service, "shrinkError", withNull);
        assertThat(m1).isEqualTo("KafkaOutboxPublishException: Kafka publish failed");

        String longMsg = "x".repeat(2500);
        KafkaOutboxPublishException longEx = new KafkaOutboxPublishException(longMsg, null);
        String m2 = ReflectionTestUtils.invokeMethod(service, "shrinkError", longEx);
        assertThat(m2.length()).isEqualTo(2000);
    }

    private static KafkaOutboxEntity outboxRow(String eventType, String topic, String key, String payloadJson) {
        OffsetDateTime now = OffsetDateTime.now();
        return KafkaOutboxEntity.builder()
                .id(UUID.randomUUID())
                .module("APP")
                .eventType(eventType)
                .topic(topic)
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
