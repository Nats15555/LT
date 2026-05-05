package com.loadtest.metrics.service;

import com.loadtest.metrics.dto.SummarizationTaskEvent;
import com.loadtest.metrics.persistence.SummarizerProviderRepository;
import com.loadtest.metrics.persistence.TaskMetricsConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SummarizationEnqueueServiceTest {

    @Mock
    private KafkaOutboxService kafkaOutboxService;
    @Mock
    private TaskMetricsConfigRepository taskMetricsConfigRepository;
    @Mock
    private SummarizerProviderRepository summarizerProviderRepository;
    @Mock
    private ExternalSummarizationPendingService externalSummarizationPendingService;

    private SummarizationEnqueueService service;

    @BeforeEach
    void setUp() {
        service = new SummarizationEnqueueService(
                kafkaOutboxService,
                taskMetricsConfigRepository,
                summarizerProviderRepository,
                externalSummarizationPendingService);
        ReflectionTestUtils.setField(service, "defaultSummarizerName", "");
        ReflectionTestUtils.setField(service, "appBaseUrl", "http://127.0.0.1:1");
    }

    @Test
    void enqueueAfterMetricsSaved_branches() {
        service.enqueueAfterMetricsSaved("bad-uuid");
        verify(kafkaOutboxService, never()).sendSummarizationEvent(any(), any());

        UUID id = UUID.randomUUID();
        when(taskMetricsConfigRepository.findSummarizerNameByTaskId(id)).thenReturn(Optional.empty());
        service.enqueueAfterMetricsSaved(id.toString());
        verify(kafkaOutboxService, never()).sendSummarizationEvent(any(), any());

        when(taskMetricsConfigRepository.findSummarizerNameByTaskId(id)).thenReturn(Optional.of("route"));
        when(summarizerProviderRepository.isSummarizerEnabled("route")).thenReturn(false);
        service.enqueueAfterMetricsSaved(id.toString());
        verify(kafkaOutboxService, never()).sendSummarizationEvent(any(), any());

        when(summarizerProviderRepository.isSummarizerEnabled("route")).thenReturn(true);
        when(summarizerProviderRepository.findProviderBySummarizerName("route")).thenReturn(Optional.of("OPENAI"));
        service.enqueueAfterMetricsSaved(id.toString());
        verify(kafkaOutboxService).sendSummarizationEvent(eq(id.toString()), any(SummarizationTaskEvent.class));
    }

    @Test
    void enqueueAfterMetricsSaved_externalProvider_registersWindow() {
        UUID id = UUID.randomUUID();
        when(taskMetricsConfigRepository.findSummarizerNameByTaskId(id)).thenReturn(Optional.of("ext"));
        when(summarizerProviderRepository.isSummarizerEnabled("ext")).thenReturn(true);
        when(summarizerProviderRepository.findProviderBySummarizerName("ext")).thenReturn(Optional.of("EXTERNAL"));

        service.enqueueAfterMetricsSaved(id.toString());

        verify(externalSummarizationPendingService).registerPendingWindow(id, "ext");
        verify(externalSummarizationPendingService).failPendingWindow(eq(id), any());
        verify(kafkaOutboxService, never()).sendSummarizationEvent(any(), any());
    }

    @Test
    void enqueueAfterMetricsSaved_usesDefaultSummarizer_andCatchesKafkaError() {
        UUID id = UUID.randomUUID();
        ReflectionTestUtils.setField(service, "defaultSummarizerName", "def");
        when(taskMetricsConfigRepository.findSummarizerNameByTaskId(id)).thenReturn(Optional.empty());
        when(summarizerProviderRepository.isSummarizerEnabled("def")).thenReturn(true);
        when(summarizerProviderRepository.findProviderBySummarizerName("def")).thenReturn(Optional.empty());
        doThrow(new RuntimeException("kafka-down")).when(kafkaOutboxService)
                .sendSummarizationEvent(eq(id.toString()), any(SummarizationTaskEvent.class));

        service.enqueueAfterMetricsSaved(id.toString());

        verify(kafkaOutboxService).sendSummarizationEvent(eq(id.toString()), any(SummarizationTaskEvent.class));
    }

    @Test
    void enqueueAfterMetricsSaved_externalRegistrationThrows_isCaught() {
        UUID id = UUID.randomUUID();
        when(taskMetricsConfigRepository.findSummarizerNameByTaskId(id)).thenReturn(Optional.of("ext"));
        when(summarizerProviderRepository.isSummarizerEnabled("ext")).thenReturn(true);
        when(summarizerProviderRepository.findProviderBySummarizerName("ext")).thenReturn(Optional.of("EXTERNAL"));
        doThrow(new RuntimeException("boom")).when(externalSummarizationPendingService).registerPendingWindow(id, "ext");

        service.enqueueAfterMetricsSaved(id.toString());

        verify(externalSummarizationPendingService).registerPendingWindow(id, "ext");
        verify(kafkaOutboxService, never()).sendSummarizationEvent(any(), any());
    }

    @Test
    void triggerExternalDispatch_blankBaseUrl_returnsFalse_lines99_100() {
        ReflectionTestUtils.setField(service, "appBaseUrl", "   ");
        Boolean dispatched = (Boolean) ReflectionTestUtils.invokeMethod(service, "triggerExternalDispatch", UUID.randomUUID());
        org.assertj.core.api.Assertions.assertThat(dispatched).isFalse();
    }

    @Test
    void triggerExternalDispatch_success_returnsTrue_lines109_110() {
        WebClient webClient = mock(WebClient.class);
        WebClient.RequestBodyUriSpec postSpec = mock(WebClient.RequestBodyUriSpec.class);
        WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);
        ReflectionTestUtils.setField(service, "webClient", webClient);

        when(webClient.post()).thenReturn(postSpec);
        when(postSpec.uri(anyString())).thenReturn(postSpec);
        when(postSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just("ok"));

        Boolean dispatched = (Boolean) ReflectionTestUtils.invokeMethod(service, "triggerExternalDispatch", UUID.randomUUID());

        org.assertj.core.api.Assertions.assertThat(dispatched).isTrue();
    }

    @Test
    void enqueueAfterMetricsSaved_whenFromDbBlank_hits51_56_branches() {
        UUID id = UUID.randomUUID();
        when(taskMetricsConfigRepository.findSummarizerNameByTaskId(id)).thenReturn(Optional.of("   "));

        service.enqueueAfterMetricsSaved(id.toString());

        verify(kafkaOutboxService, never()).sendSummarizationEvent(any(), any());
    }

    @Test
    void enqueueAfterMetricsSaved_whenDefaultSummarizerBlank_hits53_56_branch() {
        UUID id = UUID.randomUUID();
        ReflectionTestUtils.setField(service, "defaultSummarizerName", "   ");
        when(taskMetricsConfigRepository.findSummarizerNameByTaskId(id)).thenReturn(Optional.empty());

        service.enqueueAfterMetricsSaved(id.toString());

        verify(kafkaOutboxService, never()).sendSummarizationEvent(any(), any());
    }

    @Test
    void enqueueAfterMetricsSaved_whenDefaultSummarizerNull_hitsLine53NotNullFalse() {
        UUID id = UUID.randomUUID();
        ReflectionTestUtils.setField(service, "defaultSummarizerName", null);
        when(taskMetricsConfigRepository.findSummarizerNameByTaskId(id)).thenReturn(Optional.empty());

        service.enqueueAfterMetricsSaved(id.toString());

        verify(kafkaOutboxService, never()).sendSummarizationEvent(any(), any());
    }

    @Test
    void enqueueAfterMetricsSaved_filterFalseOnBlankFromDb_hitsLine51False() {
        UUID id = UUID.randomUUID();
        when(taskMetricsConfigRepository.findSummarizerNameByTaskId(id)).thenReturn(Optional.of("   "));

        service.enqueueAfterMetricsSaved(id.toString());

        verify(kafkaOutboxService, never()).sendSummarizationEvent(any(), any());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void enqueueAfterMetricsSaved_summarizerBlankNotNull_hitsLine56IsBlankBranch() {
        UUID id = UUID.randomUUID();
        Optional fromDb = mock(Optional.class);
        when(fromDb.filter(any())).thenReturn(Optional.of("   "));
        when(taskMetricsConfigRepository.findSummarizerNameByTaskId(id)).thenReturn(fromDb);

        service.enqueueAfterMetricsSaved(id.toString());

        verify(kafkaOutboxService, never()).sendSummarizationEvent(any(), any());
    }

    @Test
    void triggerExternalDispatch_nullBaseUrl_returnsFalse_hitsLine97NullBranch() {
        ReflectionTestUtils.setField(service, "appBaseUrl", null);
        Boolean dispatched = (Boolean) ReflectionTestUtils.invokeMethod(service, "triggerExternalDispatch", UUID.randomUUID());
        org.assertj.core.api.Assertions.assertThat(dispatched).isFalse();
    }

    @Test
    void enqueueAfterMetricsSaved_externalProvider_dispatchSuccessWithNullBody_noFailPending_hits74And109() {
        UUID id = UUID.randomUUID();
        when(taskMetricsConfigRepository.findSummarizerNameByTaskId(id)).thenReturn(Optional.of("ext"));
        when(summarizerProviderRepository.isSummarizerEnabled("ext")).thenReturn(true);
        when(summarizerProviderRepository.findProviderBySummarizerName("ext")).thenReturn(Optional.of("EXTERNAL"));

        WebClient webClient = mock(WebClient.class);
        WebClient.RequestBodyUriSpec postSpec = mock(WebClient.RequestBodyUriSpec.class);
        WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);
        ReflectionTestUtils.setField(service, "webClient", webClient);
        when(webClient.post()).thenReturn(postSpec);
        when(postSpec.uri(anyString())).thenReturn(postSpec);
        when(postSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.empty());

        service.enqueueAfterMetricsSaved(id.toString());

        verify(externalSummarizationPendingService, times(1)).registerPendingWindow(id, "ext");
        verify(externalSummarizationPendingService, never()).failPendingWindow(eq(id), any());
    }
}

