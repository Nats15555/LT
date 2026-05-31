package com.loadtest.summarization.consumer;

import com.loadtest.summarization.dto.SummarizationTaskEvent;
import com.loadtest.summarization.persistence.SummarizerConfig;
import com.loadtest.summarization.persistence.SummarizerModelRepository;
import com.loadtest.summarization.persistence.TaskHistoryRepository;
import com.loadtest.summarization.persistence.TestSummaryWriter;
import com.loadtest.summarization.service.OpenAiCompatibleClient;
import com.loadtest.summarization.service.PromptBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

@ExtendWith(MockitoExtension.class)
class SummarizationTaskConsumerTest {

    @Mock private TaskHistoryRepository taskHistoryRepository;
    @Mock private SummarizerModelRepository summarizerModelRepository;
    @Mock private PromptBuilder promptBuilder;
    @Mock private OpenAiCompatibleClient llmClient;
    @Mock private TestSummaryWriter testSummaryWriter;
    @Mock private Acknowledgment acknowledgment;

    private SummarizationTaskConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new SummarizationTaskConsumer(
                taskHistoryRepository,
                summarizerModelRepository,
                promptBuilder,
                llmClient,
                testSummaryWriter
        );
    }

    @Test
    void consume_invalidTaskId_ackOnly() {
        consumer.consume(new SummarizationTaskEvent("bad-uuid", "route"), acknowledgment);

        verify(acknowledgment).acknowledge();
        verify(testSummaryWriter, never()).saveSummary(any(), any(), any(), any(), any());
    }

    @Test
    void consume_noSummarizerInEventOrDb_ackOnly() {
        UUID id = UUID.randomUUID();
        when(taskHistoryRepository.getSummarizerNameByTaskId(id)).thenReturn(Optional.empty());

        consumer.consume(new SummarizationTaskEvent(id.toString(), "   "), acknowledgment);

        verify(taskHistoryRepository).getSummarizerNameByTaskId(id);
        verify(acknowledgment).acknowledge();
        verify(testSummaryWriter, never()).saveSummary(any(), any(), any(), any(), any());
    }

    @Test
    void consume_externalProvider_ackWithoutLlmCall() {
        UUID id = UUID.randomUUID();
        when(summarizerModelRepository.findByName("ext")).thenReturn(Optional.of(
                SummarizerConfig.builder()
                        .id(UUID.randomUUID())
                        .name("ext")
                        .provider("EXTERNAL")
                        .build()
        ));

        consumer.consume(new SummarizationTaskEvent(id.toString(), "ext"), acknowledgment);

        verify(summarizerModelRepository).findByName("ext");
        verify(llmClient, never()).summarize(any(), any());
        verify(testSummaryWriter, never()).saveSummary(any(), any(), any(), any(), any());
        verify(acknowledgment).acknowledge();
    }

    @Test
    void consume_openAiSuccess_savesCompletedSummary() {
        UUID id = UUID.randomUUID();
        SummarizerConfig cfg = SummarizerConfig.builder()
                .id(UUID.randomUUID())
                .name("openai-route")
                .provider("OPENAI")
                .baseUrl("http://litellm:4000")
                .modelId("gpt")
                .build();
        when(summarizerModelRepository.findByName("openai-route")).thenReturn(Optional.of(cfg));
        when(promptBuilder.buildPrompt(id)).thenReturn("prompt");
        when(llmClient.summarize(cfg, "prompt")).thenReturn("summary-text");

        consumer.consume(new SummarizationTaskEvent(id.toString(), "openai-route"), acknowledgment);

        verify(testSummaryWriter).saveSummary(
                eq(id),
                eq("AI_SUMMARY"),
                eq(Map.of("text", "summary-text", "model", "gpt", "summarizerName", "openai-route", "promptUsed", "prompt")),
                eq("COMPLETED"),
                eq(null)
        );
        verify(acknowledgment).acknowledge();
    }

    @Test
    void consume_customPrompt_skipsPromptBuilder() {
        UUID id = UUID.randomUUID();
        SummarizerConfig cfg = SummarizerConfig.builder()
                .id(UUID.randomUUID())
                .name("openai-route")
                .provider("OPENAI")
                .baseUrl("http://litellm:4000")
                .modelId("gpt")
                .build();
        when(summarizerModelRepository.findByName("openai-route")).thenReturn(Optional.of(cfg));
        when(llmClient.summarize(cfg, "user prompt")).thenReturn("summary-text");

        consumer.consume(new SummarizationTaskEvent(id.toString(), "openai-route", "user prompt"), acknowledgment);

        verifyNoInteractions(promptBuilder);
        verify(llmClient).summarize(cfg, "user prompt");
        verify(acknowledgment).acknowledge();
    }

    @Test
    void consume_whenLlmFails_savesFailed_andStillAcknowledges() {
        UUID id = UUID.randomUUID();
        SummarizerConfig cfg = SummarizerConfig.builder()
                .id(UUID.randomUUID())
                .name("openai-route")
                .provider("OPENAI")
                .baseUrl("http://litellm:4000")
                .modelId("gpt")
                .build();
        when(summarizerModelRepository.findByName("openai-route")).thenReturn(Optional.of(cfg));
        when(promptBuilder.buildPrompt(id)).thenReturn("prompt");
        doThrow(new RuntimeException("llm-down")).when(llmClient).summarize(cfg, "prompt");

        consumer.consume(new SummarizationTaskEvent(id.toString(), "openai-route"), acknowledgment);

        verify(testSummaryWriter).saveSummary(eq(id), eq("AI_SUMMARY"), any(), eq("FAILED"), eq("llm-down"));
        verify(acknowledgment).acknowledge();
    }

    @Test
    void consume_summarizerFromDbWhenEventSummarizerNameNull() {
        UUID id = UUID.randomUUID();
        when(taskHistoryRepository.getSummarizerNameByTaskId(id)).thenReturn(Optional.of("from-db"));
        SummarizerConfig cfg = SummarizerConfig.builder()
                .id(UUID.randomUUID())
                .name("from-db")
                .provider("OPENAI")
                .baseUrl("http://litellm:4000")
                .modelId("gpt")
                .build();
        when(summarizerModelRepository.findByName("from-db")).thenReturn(Optional.of(cfg));
        when(promptBuilder.buildPrompt(id)).thenReturn("p");
        when(llmClient.summarize(cfg, "p")).thenReturn("ok");

        consumer.consume(new SummarizationTaskEvent(id.toString(), null), acknowledgment);

        verify(taskHistoryRepository).getSummarizerNameByTaskId(id);
        verify(acknowledgment).acknowledge();
    }

    @Test
    void consume_whenEventSummarizerNameNull_andDbReturnsBlank_ackOnly() {
        UUID id = UUID.randomUUID();
        when(taskHistoryRepository.getSummarizerNameByTaskId(id)).thenReturn(Optional.of(" \t "));

        consumer.consume(new SummarizationTaskEvent(id.toString(), null), acknowledgment);

        verify(taskHistoryRepository).getSummarizerNameByTaskId(id);
        verify(acknowledgment).acknowledge();
        verify(testSummaryWriter, never()).saveSummary(any(), any(), any(), any(), any());
    }

    @Test
    void consume_trimsSummarizerNameFromEvent() {
        UUID id = UUID.randomUUID();
        SummarizerConfig cfg = SummarizerConfig.builder()
                .id(UUID.randomUUID())
                .name("route")
                .provider("OPENAI")
                .baseUrl("http://litellm:4000")
                .modelId("gpt")
                .build();
        when(summarizerModelRepository.findByName("route")).thenReturn(Optional.of(cfg));
        when(promptBuilder.buildPrompt(id)).thenReturn("p");
        when(llmClient.summarize(cfg, "p")).thenReturn("t");

        consumer.consume(new SummarizationTaskEvent(id.toString(), "  route  "), acknowledgment);

        verify(summarizerModelRepository).findByName("route");
        verify(acknowledgment).acknowledge();
    }

    @Test
    void consume_summarizerNotFound_savesFailed() {
        UUID id = UUID.randomUUID();
        when(summarizerModelRepository.findByName("gone")).thenReturn(Optional.empty());

        consumer.consume(new SummarizationTaskEvent(id.toString(), "gone"), acknowledgment);

        verify(testSummaryWriter).saveSummary(eq(id), eq("AI_SUMMARY"), any(), eq("FAILED"), any());
        verify(acknowledgment).acknowledge();
    }

    @Test
    void consume_llmIllegalArgument_savesFailed() {
        UUID id = UUID.randomUUID();
        SummarizerConfig cfg = SummarizerConfig.builder()
                .id(UUID.randomUUID())
                .name("x")
                .provider("OPENAI")
                .baseUrl("http://litellm:4000")
                .modelId("gpt")
                .build();
        when(summarizerModelRepository.findByName("x")).thenReturn(Optional.of(cfg));
        when(promptBuilder.buildPrompt(id)).thenReturn("p");
        doThrow(new IllegalArgumentException("bad")).when(llmClient).summarize(cfg, "p");

        consumer.consume(new SummarizationTaskEvent(id.toString(), "x"), acknowledgment);

        verify(testSummaryWriter).saveSummary(eq(id), eq("AI_SUMMARY"), any(), eq("FAILED"), eq("bad"));
        verify(acknowledgment).acknowledge();
    }

    @Test
    void consume_whenFailedSaveThrows_stillAcknowledges() {
        UUID id = UUID.randomUUID();
        SummarizerConfig cfg = SummarizerConfig.builder()
                .id(UUID.randomUUID())
                .name("x")
                .provider("OPENAI")
                .baseUrl("http://litellm:4000")
                .modelId("gpt")
                .build();
        when(summarizerModelRepository.findByName("x")).thenReturn(Optional.of(cfg));
        when(promptBuilder.buildPrompt(id)).thenReturn("p");
        doThrow(new RuntimeException("llm")).when(llmClient).summarize(cfg, "p");
        doAnswer(inv -> {
            if ("FAILED".equals(inv.getArgument(3))) {
                throw new RuntimeException("save-fail");
            }
            return null;
        }).when(testSummaryWriter).saveSummary(any(), any(), any(), any(), any());

        consumer.consume(new SummarizationTaskEvent(id.toString(), "x"), acknowledgment);

        verify(acknowledgment).acknowledge();
    }
}
