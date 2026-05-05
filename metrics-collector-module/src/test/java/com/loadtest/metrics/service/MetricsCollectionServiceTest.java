package com.loadtest.metrics.service;

import com.loadtest.metrics.config.MetricsCollectorProperties;
import com.loadtest.metrics.dto.MetricsCollectionRequest;
import com.loadtest.metrics.dto.MetricsCollectionResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MetricsCollectionServiceTest {

    @Test
    void getEffectiveUrl_resolvesHostAndQueryParamsBranches() {
        MetricsSummarizationService summarizationService = mock(MetricsSummarizationService.class);
        when(summarizationService.summarize(any(), any())).thenReturn(
                MetricsCollectionResponse.SummaryResult.builder().status("SUCCESS").summary("ok").build());
        MetricsCollectorProperties props = new MetricsCollectorProperties();
        props.setHostOverrides(Map.of("prometheus", "localhost"));
        MetricsCollectionService service = new MetricsCollectionService(summarizationService, props);

        MetricsCollectionRequest.MetricsRequestItem nullUrl =
                new MetricsCollectionRequest.MetricsRequestItem("n", "GET", null, null, null, null);
        assertThat(service.getEffectiveUrl(nullUrl)).isEmpty();

        MetricsCollectionRequest.MetricsRequestItem stringQuery =
                new MetricsCollectionRequest.MetricsRequestItem("n", "GET", "http://prometheus:9090/api/v1/query", null, "a=1&b=2", null);
        String eff1 = service.getEffectiveUrl(stringQuery);
        assertThat(eff1).contains("localhost:9090");
        assertThat(eff1).contains("a=1");
        assertThat(eff1).contains("b=2");

        MetricsCollectionRequest.MetricsRequestItem mapQuery =
                new MetricsCollectionRequest.MetricsRequestItem("n2", "GET", "http://prometheus:9090/up", null, Map.of("x", "y"), null);
        String eff2 = service.getEffectiveUrl(mapQuery);
        assertThat(eff2).contains("localhost:9090");
        assertThat(eff2).contains("x=y");
    }

    @Test
    void collectMetrics_partialAndFailedBranches() {
        MetricsSummarizationService summarizationService = mock(MetricsSummarizationService.class);
        when(summarizationService.summarize(eq("t1"), any())).thenReturn(
                MetricsCollectionResponse.SummaryResult.builder().status("SUCCESS").summary("ok").build());
        MetricsCollectorProperties props = new MetricsCollectorProperties();
        MetricsCollectionService service = new MetricsCollectionService(summarizationService, props);

        MetricsCollectionRequest partialReq = MetricsCollectionRequest.builder()
                .taskId("t1")
                .requests(List.of(new MetricsCollectionRequest.MetricsRequestItem("r1", "GET", " ", null, null, null)))
                .delaySeconds(0)
                .build();
        MetricsCollectionResponse partial = service.collectMetrics(partialReq);
        assertThat(partial.getStatus()).isEqualTo("PARTIAL");
        assertThat(partial.getMetrics()).containsKey("r1");

        MetricsCollectionRequest failedReq = MetricsCollectionRequest.builder()
                .taskId("t2")
                .requests(null)
                .delaySeconds(0)
                .build();
        MetricsCollectionResponse failed = service.collectMetrics(failedReq);
        assertThat(failed.getStatus()).isEqualTo("FAILED");
        assertThat(failed.getMessage()).contains("Failed to collect metrics");
    }

    @Test
    void resolveUrl_whenUrlMalformed_returnsOriginalFromCatch() {
        MetricsSummarizationService summarizationService = mock(MetricsSummarizationService.class);
        MetricsCollectorProperties props = new MetricsCollectorProperties();
        props.setHostOverrides(Map.of("prometheus", "localhost"));
        MetricsCollectionService service = new MetricsCollectionService(summarizationService, props);

        String resolved = (String) ReflectionTestUtils.invokeMethod(service, "resolveUrl", "://broken-url");
        assertThat(resolved).isEqualTo("://broken-url");
    }

    @Test
    void collectMetrics_delayInterrupted_andSuccessAndErrorBranches() {
        MetricsSummarizationService summarizationService = mock(MetricsSummarizationService.class);
        when(summarizationService.summarize(eq("task-delay"), any())).thenReturn(
                MetricsCollectionResponse.SummaryResult.builder().status("SUCCESS").summary("ok").build());
        MetricsCollectorProperties props = new MetricsCollectorProperties();
        MetricsCollectionService service = new MetricsCollectionService(summarizationService, props);

        WebClient webClient = mock(WebClient.class);
        WebClient.RequestBodyUriSpec uriSpec = mock(WebClient.RequestBodyUriSpec.class);
        WebClient.RequestBodySpec bodySpec = mock(WebClient.RequestBodySpec.class);
        WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);
        ReflectionTestUtils.setField(service, "webClient", webClient);

        when(uriSpec.uri(any(String.class))).thenReturn(bodySpec);
        when(bodySpec.contentType(MediaType.APPLICATION_JSON)).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just("{\"v\":1}"));

        Thread.currentThread().interrupt();
        try {
            MetricsCollectionRequest req = MetricsCollectionRequest.builder()
                    .taskId("task-delay")
                    .delaySeconds(1)
                    .requests(List.of(
                            new MetricsCollectionRequest.MetricsRequestItem("ok", "GET", "http://localhost:9090/u", null, null, null),
                            new MetricsCollectionRequest.MetricsRequestItem("bad", "GET", "http://prometheus:9090/u", null, null, null)))
                    .build();

            when(webClient.method(org.springframework.http.HttpMethod.GET))
                    .thenReturn(uriSpec)
                    .thenThrow(new RuntimeException("Failed to resolve host"));

            MetricsCollectionResponse response = service.collectMetrics(req);
            assertThat(response.getStatus()).isEqualTo("PARTIAL");
            assertThat(response.getMetrics()).containsKey("ok").containsKey("bad");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void collectMetrics_handlesWebClientResponseExceptionBranch() {
        MetricsSummarizationService summarizationService = mock(MetricsSummarizationService.class);
        when(summarizationService.summarize(eq("task-http"), any())).thenReturn(
                MetricsCollectionResponse.SummaryResult.builder().status("SUCCESS").summary("ok").build());
        MetricsCollectorProperties props = new MetricsCollectorProperties();
        MetricsCollectionService service = new MetricsCollectionService(summarizationService, props);

        WebClient webClient = mock(WebClient.class);
        ReflectionTestUtils.setField(service, "webClient", webClient);
        when(webClient.method(org.springframework.http.HttpMethod.GET)).thenThrow(
                WebClientResponseException.create(
                        HttpStatus.BAD_GATEWAY.value(),
                        "Bad Gateway",
                        HttpHeaders.EMPTY,
                        "x".getBytes(),
                        null));

        MetricsCollectionRequest req = MetricsCollectionRequest.builder()
                .taskId("task-http")
                .delaySeconds(0)
                .requests(List.of(new MetricsCollectionRequest.MetricsRequestItem("r", "GET", "http://localhost:9090/m", null, null, null)))
                .build();

        MetricsCollectionResponse response = service.collectMetrics(req);
        assertThat(response.getStatus()).isEqualTo("PARTIAL");
        assertThat(response.getMetrics().get("r")).isNotNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void executeRequest_getAndBlankAndRawBranches() {
        MetricsSummarizationService summarizationService = mock(MetricsSummarizationService.class);
        MetricsCollectorProperties props = new MetricsCollectorProperties();
        MetricsCollectionService service = new MetricsCollectionService(summarizationService, props);

        WebClient webClient = mock(WebClient.class);
        WebClient.RequestBodyUriSpec uriSpec = mock(WebClient.RequestBodyUriSpec.class);
        WebClient.RequestBodySpec bodySpec = mock(WebClient.RequestBodySpec.class);
        WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);
        ReflectionTestUtils.setField(service, "webClient", webClient);

        when(webClient.method(org.springframework.http.HttpMethod.GET)).thenReturn(uriSpec);
        when(uriSpec.uri(any(String.class))).thenReturn(bodySpec);
        when(bodySpec.contentType(MediaType.APPLICATION_JSON)).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(String.class))
                .thenReturn(Mono.just("{\"m\":1}"))
                .thenReturn(Mono.just(""))
                .thenReturn(Mono.just("plain-text"))
                .thenReturn(Mono.just(""))
                .thenReturn(Mono.just(""));

        MetricsCollectionRequest.MetricsRequestItem getReq = new MetricsCollectionRequest.MetricsRequestItem(
                "n", null, "http://localhost:9090/a", Map.of("H", "V"), Map.of("p", "1"), null);
        Map<String, Object> parsed = (Map<String, Object>) ReflectionTestUtils.invokeMethod(service, "executeRequest", getReq);
        assertThat(parsed).containsEntry("m", 1);

        MetricsCollectionRequest.MetricsRequestItem blankReq = new MetricsCollectionRequest.MetricsRequestItem(
                "n2", "GET", "http://localhost:9090/b", null, null, null);
        Map<String, Object> blank = (Map<String, Object>) ReflectionTestUtils.invokeMethod(service, "executeRequest", blankReq);
        assertThat(blank).containsEntry("_raw", "");

        MetricsCollectionRequest.MetricsRequestItem rawReq = new MetricsCollectionRequest.MetricsRequestItem(
                "n3", "GET", "http://localhost:9090/c", null, null, null);
        Map<String, Object> raw = (Map<String, Object>) ReflectionTestUtils.invokeMethod(service, "executeRequest", rawReq);
        assertThat(raw).containsEntry("_raw", "plain-text");

        MetricsCollectionRequest.MetricsRequestItem blankMethodReq = new MetricsCollectionRequest.MetricsRequestItem(
                "n4", "   ", "http://localhost:9090/d", null, null, null);
        Map<String, Object> blankMethod = (Map<String, Object>) ReflectionTestUtils.invokeMethod(service, "executeRequest", blankMethodReq);
        assertThat(blankMethod).containsEntry("_raw", "");

        MetricsCollectionRequest.MetricsRequestItem emptyHeadersReq = new MetricsCollectionRequest.MetricsRequestItem(
                "n5", "GET", "http://localhost:9090/e", Map.of(), null, null);
        Map<String, Object> emptyHeaders = (Map<String, Object>) ReflectionTestUtils.invokeMethod(service, "executeRequest", emptyHeadersReq);
        assertThat(emptyHeaders).containsEntry("_raw", "");
    }

    @Test
    @SuppressWarnings("unchecked")
    void executeRequest_postBodySerializationBranch() {
        MetricsSummarizationService summarizationService = mock(MetricsSummarizationService.class);
        MetricsCollectorProperties props = new MetricsCollectorProperties();
        MetricsCollectionService service = new MetricsCollectionService(summarizationService, props);

        WebClient webClient = mock(WebClient.class);
        WebClient.RequestBodyUriSpec uriSpec = mock(WebClient.RequestBodyUriSpec.class);
        WebClient.RequestBodySpec bodySpec = mock(WebClient.RequestBodySpec.class);
        WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);
        ReflectionTestUtils.setField(service, "webClient", webClient);

        when(webClient.method(org.springframework.http.HttpMethod.POST)).thenReturn(uriSpec);
        when(uriSpec.uri(any(String.class))).thenReturn(bodySpec);
        when(bodySpec.contentType(MediaType.APPLICATION_JSON)).thenReturn(bodySpec);
        when(bodySpec.bodyValue(any())).thenAnswer(inv -> bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(String.class))
                .thenReturn(Mono.just("{\"ok\":true}"))
                .thenReturn(Mono.just("{\"okString\":true}"));

        MetricsCollectionRequest.MetricsRequestItem postReq = new MetricsCollectionRequest.MetricsRequestItem(
                "p", "POST", "http://localhost:9090/post", null, null, Map.of("x", 1));
        Map<String, Object> response = (Map<String, Object>) ReflectionTestUtils.invokeMethod(service, "executeRequest", postReq);
        assertThat(response).containsEntry("ok", true);

        MetricsCollectionRequest.MetricsRequestItem postStringBodyReq = new MetricsCollectionRequest.MetricsRequestItem(
                "ps", "POST", "http://localhost:9090/post2", null, null, "{\"x\":1}");
        Map<String, Object> responseString = (Map<String, Object>) ReflectionTestUtils.invokeMethod(service, "executeRequest", postStringBodyReq);
        assertThat(responseString).containsEntry("okString", true);
    }

    @Test
    void resolveUrl_andGetEffectiveUrl_edgeBranches() {
        MetricsSummarizationService summarizationService = mock(MetricsSummarizationService.class);
        MetricsCollectorProperties props = new MetricsCollectorProperties();
        props.setHostOverrides(Map.of("prometheus", "localhost"));
        MetricsCollectionService service = new MetricsCollectionService(summarizationService, props);

        String noHost = (String) ReflectionTestUtils.invokeMethod(service, "resolveUrl", "mailto:test@example.com");
        assertThat(noHost).isEqualTo("mailto:test@example.com");

        String noOverride = (String) ReflectionTestUtils.invokeMethod(service, "resolveUrl", "http://unknown:9090/api");
        assertThat(noOverride).isEqualTo("http://unknown:9090/api");

        MetricsCollectionRequest.MetricsRequestItem blankUrl =
                new MetricsCollectionRequest.MetricsRequestItem("n", "GET", "   ", null, null, null);
        assertThat(service.getEffectiveUrl(blankUrl)).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildFullUri_andExecuteRequest_extraBranches() {
        String fromString = (String) ReflectionTestUtils.invokeMethod(
                MetricsCollectionService.class,
                "buildFullUri",
                "http://localhost:9090/api",
                "a&b=2&=x");
        assertThat(fromString).contains("b=2");

        String fromBlankString = (String) ReflectionTestUtils.invokeMethod(
                MetricsCollectionService.class,
                "buildFullUri",
                "http://localhost:9090/api",
                "   ");
        assertThat(fromBlankString).isEqualTo("http://localhost:9090/api");

        String fromNonStringNonMap = (String) ReflectionTestUtils.invokeMethod(
                MetricsCollectionService.class,
                "buildFullUri",
                "http://localhost:9090/api",
                123);
        assertThat(fromNonStringNonMap).isEqualTo("http://localhost:9090/api");

        Map<String, Object> queryWithNull = new HashMap<>();
        queryWithNull.put("k", null);
        String fromMap = (String) ReflectionTestUtils.invokeMethod(
                MetricsCollectionService.class,
                "buildFullUri",
                "http://localhost:9090/api",
                queryWithNull);
        assertThat(fromMap).contains("k=");

        MetricsSummarizationService summarizationService = mock(MetricsSummarizationService.class);
        MetricsCollectionService service = new MetricsCollectionService(summarizationService, new MetricsCollectorProperties());
        WebClient webClient = mock(WebClient.class);
        WebClient.RequestBodyUriSpec uriSpec = mock(WebClient.RequestBodyUriSpec.class);
        WebClient.RequestBodySpec bodySpec = mock(WebClient.RequestBodySpec.class);
        WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);
        ReflectionTestUtils.setField(service, "webClient", webClient);

        when(webClient.method(org.springframework.http.HttpMethod.HEAD)).thenReturn(uriSpec);
        when(webClient.method(org.springframework.http.HttpMethod.POST)).thenReturn(uriSpec);
        when(uriSpec.uri(any(String.class))).thenReturn(bodySpec);
        when(bodySpec.contentType(MediaType.APPLICATION_JSON)).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(String.class))
                .thenReturn(Mono.just("{}"))
                .thenReturn(Mono.empty());

        MetricsCollectionRequest.MetricsRequestItem headReq = new MetricsCollectionRequest.MetricsRequestItem(
                "h", "HEAD", "http://localhost:9090/head", Map.of("X", "1"), null, "ignored");
        Map<String, Object> head = (Map<String, Object>) ReflectionTestUtils.invokeMethod(service, "executeRequest", headReq);
        assertThat(head).isEmpty();

        MetricsCollectionRequest.MetricsRequestItem postNullBodyReq = new MetricsCollectionRequest.MetricsRequestItem(
                "", "POST", "http://localhost:9090/post", null, null, null);
        Map<String, Object> nullResp = (Map<String, Object>) ReflectionTestUtils.invokeMethod(service, "executeRequest", postNullBodyReq);
        assertThat(nullResp).containsEntry("_raw", "");
    }

    @Test
    void collectMetrics_usesUrlAsKey_andHintBranchWithNullHost() {
        MetricsSummarizationService summarizationService = mock(MetricsSummarizationService.class);
        when(summarizationService.summarize(eq("task-key"), any())).thenReturn(
                MetricsCollectionResponse.SummaryResult.builder().status("SUCCESS").summary("ok").build());
        MetricsCollectionService service = new MetricsCollectionService(summarizationService, new MetricsCollectorProperties());

        WebClient webClient = mock(WebClient.class);
        ReflectionTestUtils.setField(service, "webClient", webClient);
        when(webClient.method(org.springframework.http.HttpMethod.GET))
                .thenThrow(new RuntimeException("Failed to resolve service"));

        MetricsCollectionRequest req = MetricsCollectionRequest.builder()
                .taskId("task-key")
                .delaySeconds(1)
                .requests(List.of(new MetricsCollectionRequest.MetricsRequestItem("   ", "GET", "http://:9090/u", null, null, null)))
                .build();
        MetricsCollectionResponse response = service.collectMetrics(req);
        assertThat(response.getStatus()).isEqualTo("PARTIAL");
        assertThat(response.getMetrics()).containsKey("http://:9090/u");
    }

    @Test
    void resolveUrl_blankUrl_andHostNullBranches() {
        MetricsSummarizationService summarizationService = mock(MetricsSummarizationService.class);
        MetricsCollectorProperties props = new MetricsCollectorProperties();
        props.setHostOverrides(Map.of("prometheus", "localhost"));
        MetricsCollectionService service = new MetricsCollectionService(summarizationService, props);

        String blank = (String) ReflectionTestUtils.invokeMethod(service, "resolveUrl", "   ");
        assertThat(blank).isEqualTo("   ");

        String hostNull = (String) ReflectionTestUtils.invokeMethod(service, "resolveUrl", "file:/tmp/a.txt");
        assertThat(hostNull).isEqualTo("file:/tmp/a.txt");
    }

    @Test
    void resolveUrl_nullUrl_andNullOrEmptyOverrides_branchesLine47() {
        MetricsSummarizationService summarizationService = mock(MetricsSummarizationService.class);

        MetricsCollectorProperties propsNullOverrides = new MetricsCollectorProperties();
        propsNullOverrides.setHostOverrides(null);
        MetricsCollectionService s1 = new MetricsCollectionService(summarizationService, propsNullOverrides);
        String nullUrl = (String) ReflectionTestUtils.invokeMethod(s1, "resolveUrl", (String) null);
        assertThat(nullUrl).isNull();
        String withNullOverrides = (String) ReflectionTestUtils.invokeMethod(s1, "resolveUrl", "http://prometheus:9090/a");
        assertThat(withNullOverrides).isEqualTo("http://prometheus:9090/a");

        MetricsCollectorProperties propsEmptyOverrides = new MetricsCollectorProperties();
        propsEmptyOverrides.setHostOverrides(Map.of());
        MetricsCollectionService s2 = new MetricsCollectionService(summarizationService, propsEmptyOverrides);
        String withEmptyOverrides = (String) ReflectionTestUtils.invokeMethod(s2, "resolveUrl", "http://prometheus:9090/a");
        assertThat(withEmptyOverrides).isEqualTo("http://prometheus:9090/a");
    }

    @Test
    void collectMetrics_delayNull_skipsSleep_hitsLine75False() {
        MetricsSummarizationService summarizationService = mock(MetricsSummarizationService.class);
        when(summarizationService.summarize(eq("task-delay-null"), any())).thenReturn(
                MetricsCollectionResponse.SummaryResult.builder().status("SUCCESS").summary("ok").build());
        MetricsCollectionService service = new MetricsCollectionService(summarizationService, new MetricsCollectorProperties());

        WebClient webClient = mock(WebClient.class);
        WebClient.RequestBodyUriSpec uriSpec = mock(WebClient.RequestBodyUriSpec.class);
        WebClient.RequestBodySpec bodySpec = mock(WebClient.RequestBodySpec.class);
        WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);
        ReflectionTestUtils.setField(service, "webClient", webClient);
        when(webClient.method(org.springframework.http.HttpMethod.GET)).thenReturn(uriSpec);
        when(uriSpec.uri(any(String.class))).thenReturn(bodySpec);
        when(bodySpec.contentType(MediaType.APPLICATION_JSON)).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just("{\"v\":1}"));

        MetricsCollectionRequest req = MetricsCollectionRequest.builder()
                .taskId("task-delay-null")
                .delaySeconds(null)
                .requests(List.of(new MetricsCollectionRequest.MetricsRequestItem("ok", "GET", "http://localhost:9090/u", null, null, null)))
                .build();
        MetricsCollectionResponse response = service.collectMetrics(req);
        assertThat(response.getStatus()).isEqualTo("SUCCESS");
    }

    @Test
    void collectMetrics_delayNonNullButNotPositive_hitsLine75FalseSecondOperand() {
        MetricsSummarizationService summarizationService = mock(MetricsSummarizationService.class);
        when(summarizationService.summarize(eq("task-delay-negative"), any())).thenReturn(
                MetricsCollectionResponse.SummaryResult.builder().status("SUCCESS").summary("ok").build());
        MetricsCollectionService service = new MetricsCollectionService(summarizationService, new MetricsCollectorProperties());

        WebClient webClient = mock(WebClient.class);
        WebClient.RequestBodyUriSpec uriSpec = mock(WebClient.RequestBodyUriSpec.class);
        WebClient.RequestBodySpec bodySpec = mock(WebClient.RequestBodySpec.class);
        WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);
        ReflectionTestUtils.setField(service, "webClient", webClient);
        when(webClient.method(org.springframework.http.HttpMethod.GET)).thenReturn(uriSpec);
        when(uriSpec.uri(any(String.class))).thenReturn(bodySpec);
        when(bodySpec.contentType(MediaType.APPLICATION_JSON)).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just("{\"v\":1}"));

        MetricsCollectionRequest req = MetricsCollectionRequest.builder()
                .taskId("task-delay-negative")
                .delaySeconds(-1)
                .requests(List.of(new MetricsCollectionRequest.MetricsRequestItem("ok", "GET", "http://localhost:9090/u", null, null, null)))
                .build();
        MetricsCollectionResponse response = service.collectMetrics(req);
        assertThat(response.getStatus()).isEqualTo("SUCCESS");
    }

    @Test
    void collectMetrics_keyUsesUrl_whenNameNullAndBlank_hitsLine88BothFalseCases() {
        MetricsSummarizationService summarizationService = mock(MetricsSummarizationService.class);
        when(summarizationService.summarize(eq("task-key-false"), any())).thenReturn(
                MetricsCollectionResponse.SummaryResult.builder().status("SUCCESS").summary("ok").build());
        MetricsCollectionService service = new MetricsCollectionService(summarizationService, new MetricsCollectorProperties());

        WebClient webClient = mock(WebClient.class);
        WebClient.RequestBodyUriSpec uriSpec = mock(WebClient.RequestBodyUriSpec.class);
        WebClient.RequestBodySpec bodySpec = mock(WebClient.RequestBodySpec.class);
        WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);
        ReflectionTestUtils.setField(service, "webClient", webClient);
        when(webClient.method(org.springframework.http.HttpMethod.GET)).thenReturn(uriSpec);
        when(uriSpec.uri(any(String.class))).thenReturn(bodySpec);
        when(bodySpec.contentType(MediaType.APPLICATION_JSON)).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just("{}"));

        MetricsCollectionRequest req = MetricsCollectionRequest.builder()
                .taskId("task-key-false")
                .delaySeconds(0)
                .requests(List.of(
                        new MetricsCollectionRequest.MetricsRequestItem(null, "GET", "http://localhost:9090/n1", null, null, null),
                        new MetricsCollectionRequest.MetricsRequestItem("   ", "GET", "http://localhost:9090/n2", null, null, null)))
                .build();
        MetricsCollectionResponse response = service.collectMetrics(req);
        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(response.getMetrics())
                .containsKey("http://localhost:9090/n1")
                .containsKey("http://localhost:9090/n2");
    }

    @Test
    void collectMetrics_errorMessageNull_branchLine98() {
        MetricsSummarizationService summarizationService = mock(MetricsSummarizationService.class);
        when(summarizationService.summarize(any(), any())).thenReturn(
                MetricsCollectionResponse.SummaryResult.builder().status("SUCCESS").summary("ok").build());
        MetricsCollectionService service = new MetricsCollectionService(summarizationService, new MetricsCollectorProperties());

        WebClient webClient = mock(WebClient.class);
        ReflectionTestUtils.setField(service, "webClient", webClient);

        when(webClient.method(org.springframework.http.HttpMethod.GET))
                .thenThrow(new RuntimeException((String) null));
        MetricsCollectionRequest reqNullMsg = MetricsCollectionRequest.builder()
                .taskId("task-null-msg")
                .delaySeconds(0)
                .requests(List.of(new MetricsCollectionRequest.MetricsRequestItem("r1", "GET", "http://localhost:9090/u1", null, null, null)))
                .build();
        MetricsCollectionResponse nullMsgResp = service.collectMetrics(reqNullMsg);
        assertThat(nullMsgResp.getStatus()).isEqualTo("FAILED");
    }

    @Test
    void collectMetrics_nameOrServiceNotKnown_branchLine98() {
        MetricsSummarizationService summarizationService = mock(MetricsSummarizationService.class);
        when(summarizationService.summarize(any(), any())).thenReturn(
                MetricsCollectionResponse.SummaryResult.builder().status("SUCCESS").summary("ok").build());
        MetricsCollectionService service = new MetricsCollectionService(summarizationService, new MetricsCollectorProperties());

        WebClient webClient = mock(WebClient.class);
        ReflectionTestUtils.setField(service, "webClient", webClient);
        when(webClient.method(org.springframework.http.HttpMethod.GET))
                .thenThrow(new RuntimeException("Name or service not known: prometheus"));
        MetricsCollectionRequest reqNameOrService = MetricsCollectionRequest.builder()
                .taskId("task-name-or-service")
                .delaySeconds(0)
                .requests(List.of(new MetricsCollectionRequest.MetricsRequestItem("r2", "GET", "http://prometheus:9090/u2", null, null, null)))
                .build();
        MetricsCollectionResponse nameOrServiceResp = service.collectMetrics(reqNameOrService);
        assertThat(nameOrServiceResp.getStatus()).isEqualTo("PARTIAL");
    }

    @Test
    void collectMetrics_nameOrServiceNotKnown_withHostNull_hitsLine101False() {
        MetricsSummarizationService summarizationService = mock(MetricsSummarizationService.class);
        when(summarizationService.summarize(any(), any())).thenReturn(
                MetricsCollectionResponse.SummaryResult.builder().status("SUCCESS").summary("ok").build());
        MetricsCollectionService service = new MetricsCollectionService(summarizationService, new MetricsCollectorProperties());

        WebClient webClient = mock(WebClient.class);
        ReflectionTestUtils.setField(service, "webClient", webClient);
        when(webClient.method(org.springframework.http.HttpMethod.GET))
                .thenThrow(new RuntimeException("Name or service not known: transport"));

        MetricsCollectionRequest req = MetricsCollectionRequest.builder()
                .taskId("task-host-null")
                .delaySeconds(0)
                .requests(List.of(new MetricsCollectionRequest.MetricsRequestItem("r3", "GET", "file:/tmp/metrics.json", null, null, null)))
                .build();

        MetricsCollectionResponse response = service.collectMetrics(req);
        assertThat(response.getStatus()).isEqualTo("PARTIAL");
    }

    @Test
    void collectMetrics_nameOrServiceNotKnown_withNonHttpUrl_remainsPartial() {
        MetricsSummarizationService summarizationService = mock(MetricsSummarizationService.class);
        when(summarizationService.summarize(any(), any())).thenReturn(
                MetricsCollectionResponse.SummaryResult.builder().status("SUCCESS").summary("ok").build());
        MetricsCollectionService service = new MetricsCollectionService(summarizationService, new MetricsCollectorProperties());

        MetricsCollectionRequest req = MetricsCollectionRequest.builder()
                .taskId("task-non-http")
                .delaySeconds(0)
                .requests(List.of(new MetricsCollectionRequest.MetricsRequestItem("r4", "GET", "mailto:test@example.com", null, null, null)))
                .build();

        MetricsCollectionResponse response = service.collectMetrics(req);
        assertThat(response.getStatus()).isEqualTo("PARTIAL");
    }

    @Test
    void buildResolveHint_hostNullAndHostPresent_branchesForLine101() {
        String noHostHint = (String) ReflectionTestUtils.invokeMethod(
                MetricsCollectionService.class,
                "buildResolveHint",
                "mailto:test@example.com",
                new RuntimeException("Name or service not known"));
        assertThat(noHostHint).isEmpty();

        String hostHint = (String) ReflectionTestUtils.invokeMethod(
                MetricsCollectionService.class,
                "buildResolveHint",
                "http://prometheus:9090/api",
                new RuntimeException("Name or service not known"));
        assertThat(hostHint).contains("metrics.host-overrides.prometheus=localhost");

        String malformedUrlHint = (String) ReflectionTestUtils.invokeMethod(
                MetricsCollectionService.class,
                "buildResolveHint",
                "://broken-url",
                new RuntimeException("Failed to resolve host"));
        assertThat(malformedUrlHint).isEmpty();
    }

    @Test
    void executeRequest_nullUrl_hitsLine178TrueBranch() {
        MetricsSummarizationService summarizationService = mock(MetricsSummarizationService.class);
        MetricsCollectionService service = new MetricsCollectionService(summarizationService, new MetricsCollectorProperties());
        MetricsCollectionRequest.MetricsRequestItem bad =
                new MetricsCollectionRequest.MetricsRequestItem("n", "GET", null, null, null, null);

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "executeRequest", bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Request URL is required");
    }
}

