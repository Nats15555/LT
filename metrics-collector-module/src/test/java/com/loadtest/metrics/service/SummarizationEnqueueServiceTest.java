package com.loadtest.metrics.service;

import com.loadtest.metrics.dto.SummarizationTaskEvent;
import com.loadtest.metrics.persistence.SummarizerProviderRepository;
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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    private SummarizerProviderRepository summarizerProviderRepository;
    @Mock
    private ExternalSummarizationPendingService externalSummarizationPendingService;

    private SummarizationEnqueueService service;

    @BeforeEach
    void setUp() {
        service = new SummarizationEnqueueService(
                kafkaOutboxService,
                summarizerProviderRepository,
                externalSummarizationPendingService);
        ReflectionTestUtils.setField(service, "appBaseUrl", "http://127.0.0.1:1");
    }

    @Test
    void enqueueSummarizationForTask_branches() {
        service.enqueueSummarizationForTask("bad-uuid", "route");
        verify(kafkaOutboxService, never()).sendSummarizationEvent(any(), any());

        UUID id = UUID.randomUUID();
        service.enqueueSummarizationForTask(id.toString(), "  ");
        verify(kafkaOutboxService, never()).sendSummarizationEvent(any(), any());

        when(summarizerProviderRepository.isSummarizerEnabled("route")).thenReturn(false);
        service.enqueueSummarizationForTask(id.toString(), "route");
        verify(kafkaOutboxService, never()).sendSummarizationEvent(any(), any());

        when(summarizerProviderRepository.isSummarizerEnabled("route")).thenReturn(true);
        when(summarizerProviderRepository.findProviderBySummarizerName("route")).thenReturn(Optional.of("OPENAI"));
        service.enqueueSummarizationForTask(id.toString(), "route");
        verify(kafkaOutboxService).sendSummarizationEvent(eq(id.toString()), any(SummarizationTaskEvent.class));
    }

    @Test
    void enqueueSummarizationForTask_externalProvider_registersWindow() {
        UUID id = UUID.randomUUID();
        when(summarizerProviderRepository.isSummarizerEnabled("ext")).thenReturn(true);
        when(summarizerProviderRepository.findProviderBySummarizerName("ext")).thenReturn(Optional.of("EXTERNAL"));

        assertThatThrownBy(() -> service.enqueueSummarizationForTask(id.toString(), "ext"))
                .isInstanceOf(SummarizationEnqueueException.class);

        verify(externalSummarizationPendingService).registerPendingWindow(id, "ext");
        verify(externalSummarizationPendingService).failPendingWindow(eq(id), any());
        verify(kafkaOutboxService, never()).sendSummarizationEvent(any(), any());
    }

    @Test
    void enqueueSummarizationForTask_catchesKafkaError() {
        UUID id = UUID.randomUUID();
        when(summarizerProviderRepository.isSummarizerEnabled("def")).thenReturn(true);
        when(summarizerProviderRepository.findProviderBySummarizerName("def")).thenReturn(Optional.empty());
        doThrow(new RuntimeException("kafka-down")).when(kafkaOutboxService)
                .sendSummarizationEvent(eq(id.toString()), any(SummarizationTaskEvent.class));

        service.enqueueSummarizationForTask(id.toString(), "def");

        verify(kafkaOutboxService).sendSummarizationEvent(eq(id.toString()), any(SummarizationTaskEvent.class));
    }

    @Test
    void enqueueSummarizationForTask_externalRegistrationThrows() {
        UUID id = UUID.randomUUID();
        when(summarizerProviderRepository.isSummarizerEnabled("ext")).thenReturn(true);
        when(summarizerProviderRepository.findProviderBySummarizerName("ext")).thenReturn(Optional.of("EXTERNAL"));
        doThrow(new RuntimeException("boom")).when(externalSummarizationPendingService).registerPendingWindow(id, "ext");

        assertThatThrownBy(() -> service.enqueueSummarizationForTask(id.toString(), "ext"))
                .isInstanceOf(SummarizationEnqueueException.class);

        verify(externalSummarizationPendingService).registerPendingWindow(id, "ext");
        verify(kafkaOutboxService, never()).sendSummarizationEvent(any(), any());
    }

    @Test
    void triggerExternalDispatch_blankBaseUrl_returnsFalse() {
        ReflectionTestUtils.setField(service, "appBaseUrl", "   ");
        Boolean dispatched = (Boolean) ReflectionTestUtils.invokeMethod(service, "triggerExternalDispatch", UUID.randomUUID());
        org.assertj.core.api.Assertions.assertThat(dispatched).isFalse();
    }

    @Test
    void triggerExternalDispatch_success_returnsTrue() {
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
    void enqueueSummarizationForTask_externalProvider_dispatchSuccessWithNullBody() {
        UUID id = UUID.randomUUID();
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

        service.enqueueSummarizationForTask(id.toString(), "ext");

        verify(externalSummarizationPendingService, times(1)).registerPendingWindow(id, "ext");
        verify(externalSummarizationPendingService, never()).failPendingWindow(eq(id), any());
    }
}
