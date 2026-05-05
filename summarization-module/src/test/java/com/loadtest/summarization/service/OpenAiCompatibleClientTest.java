package com.loadtest.summarization.service;

import com.loadtest.summarization.persistence.SummarizerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenAiCompatibleClientTest {

    @Test
    void summarize_throwsWhenBaseUrlOrModelMissing() {
        OpenAiCompatibleClient client = new OpenAiCompatibleClient(mock(WebClient.class));
        UUID id = UUID.randomUUID();
        SummarizerConfig noBase = SummarizerConfig.builder().id(id).name("r").modelId("m").build();
        SummarizerConfig noModel = SummarizerConfig.builder().id(id).name("r").baseUrl("http://x").build();
        SummarizerConfig blankBase = SummarizerConfig.builder().id(id).name("r").baseUrl(" \t ").modelId("m").build();
        SummarizerConfig blankModel = SummarizerConfig.builder().id(id).name("r").baseUrl("http://x").modelId("  ").build();

        assertThatThrownBy(() -> client.summarize(noBase, "p"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("base_url");
        assertThatThrownBy(() -> client.summarize(noModel, "p"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("model_id");
        assertThatThrownBy(() -> client.summarize(blankBase, "p"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("base_url");
        assertThatThrownBy(() -> client.summarize(blankModel, "p"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("model_id");
    }

    @Test
    @SuppressWarnings("unchecked")
    void summarize_routeUsesIdWhenNameBlank() {
        WebClient webClient = mock(WebClient.class);
        WebClient.RequestBodyUriSpec post = mock(WebClient.RequestBodyUriSpec.class);
        WebClient.RequestBodySpec bodySpec = mock(WebClient.RequestBodySpec.class);
        WebClient.RequestHeadersSpec<?> headersSpec = mock(WebClient.RequestHeadersSpec.class);
        WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);

        when(webClient.post()).thenReturn(post);
        when(post.uri(anyString())).thenReturn(bodySpec);
        when(bodySpec.contentType(MediaType.APPLICATION_JSON)).thenReturn(bodySpec);
        when(bodySpec.bodyValue(any())).thenReturn((WebClient.RequestHeadersSpec) headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(Map.class)).thenReturn(Mono.just(
                Map.of("choices", List.of(Map.of("message", Map.of("content", "  x  "))))
        ));

        UUID id = UUID.randomUUID();
        SummarizerConfig cfg = SummarizerConfig.builder()
                .id(id).name("   ").provider("OPENAI").baseUrl("http://h/").modelId("m").build();
        OpenAiCompatibleClient client = new OpenAiCompatibleClient(webClient);
        assertThat(client.summarize(cfg, "p")).isEqualTo("x");
    }

    @Test
    @SuppressWarnings("unchecked")
    void summarize_routeUsesIdWhenNameNull() {
        WebClient webClient = mock(WebClient.class);
        WebClient.RequestBodyUriSpec post = mock(WebClient.RequestBodyUriSpec.class);
        WebClient.RequestBodySpec bodySpec = mock(WebClient.RequestBodySpec.class);
        WebClient.RequestHeadersSpec<?> headersSpec = mock(WebClient.RequestHeadersSpec.class);
        WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);

        when(webClient.post()).thenReturn(post);
        when(post.uri(anyString())).thenReturn(bodySpec);
        when(bodySpec.contentType(MediaType.APPLICATION_JSON)).thenReturn(bodySpec);
        when(bodySpec.bodyValue(any())).thenReturn((WebClient.RequestHeadersSpec) headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(Map.class)).thenReturn(Mono.just(
                Map.of("choices", List.of(Map.of("message", Map.of("content", "ok"))))
        ));

        UUID id = UUID.randomUUID();
        SummarizerConfig cfg = SummarizerConfig.builder()
                .id(id).name(null).provider("OPENAI").baseUrl("http://h/").modelId("m").build();

        assertThat(new OpenAiCompatibleClient(webClient).summarize(cfg, "p")).isEqualTo("ok");
    }

    @Test
    @SuppressWarnings("unchecked")
    void summarize_blankApiKeyResolved_skipsAuthorizationHeader() {
        WebClient webClient = mock(WebClient.class);
        WebClient.RequestBodyUriSpec post = mock(WebClient.RequestBodyUriSpec.class);
        WebClient.RequestBodySpec bodySpec = mock(WebClient.RequestBodySpec.class);
        WebClient.RequestHeadersSpec<?> headersSpec = mock(WebClient.RequestHeadersSpec.class);
        WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);

        when(webClient.post()).thenReturn(post);
        when(post.uri(anyString())).thenReturn(bodySpec);
        when(bodySpec.contentType(MediaType.APPLICATION_JSON)).thenReturn(bodySpec);
        when(bodySpec.bodyValue(any())).thenReturn((WebClient.RequestHeadersSpec) headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(Map.class)).thenReturn(Mono.just(
                Map.of("choices", List.of(Map.of("message", Map.of("content", "z"))))
        ));

        SummarizerConfig cfg = SummarizerConfig.builder()
                .id(UUID.randomUUID()).name("r").baseUrl("http://h/").modelId("m").apiKeyResolved(" \t ").build();

        assertThat(new OpenAiCompatibleClient(webClient).summarize(cfg, "p")).isEqualTo("z");
        verify(headersSpec, never()).header(anyString(), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void summarize_choicesNotList_throwsUnexpectedStructure() {
        WebClient webClient = mock(WebClient.class);
        WebClient.RequestBodyUriSpec post = mock(WebClient.RequestBodyUriSpec.class);
        WebClient.RequestBodySpec bodySpec = mock(WebClient.RequestBodySpec.class);
        WebClient.RequestHeadersSpec<?> headersSpec = mock(WebClient.RequestHeadersSpec.class);
        WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);

        when(webClient.post()).thenReturn(post);
        when(post.uri(anyString())).thenReturn(bodySpec);
        when(bodySpec.contentType(MediaType.APPLICATION_JSON)).thenReturn(bodySpec);
        when(bodySpec.bodyValue(any())).thenReturn((WebClient.RequestHeadersSpec) headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(Map.class)).thenReturn(Mono.just(Map.of("choices", "not-a-list")));

        SummarizerConfig cfg = SummarizerConfig.builder()
                .id(UUID.randomUUID()).name("r").baseUrl("http://h").modelId("m").build();

        assertThatThrownBy(() -> new OpenAiCompatibleClient(webClient).summarize(cfg, "p"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Unexpected response structure");
    }

    @Test
    @SuppressWarnings("unchecked")
    void summarize_checkedExceptionFromResponse_wrappedAsRuntime() {
        WebClient webClient = mock(WebClient.class);
        WebClient.RequestBodyUriSpec post = mock(WebClient.RequestBodyUriSpec.class);
        WebClient.RequestBodySpec bodySpec = mock(WebClient.RequestBodySpec.class);
        WebClient.RequestHeadersSpec<?> headersSpec = mock(WebClient.RequestHeadersSpec.class);
        WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);

        when(webClient.post()).thenReturn(post);
        when(post.uri(anyString())).thenReturn(bodySpec);
        when(bodySpec.contentType(MediaType.APPLICATION_JSON)).thenReturn(bodySpec);
        when(bodySpec.bodyValue(any())).thenReturn((WebClient.RequestHeadersSpec) headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);

        Map<String, Object> throwingOnChoices = new HashMap<>() {
            @Override
            public Object get(Object key) {
                if ("choices".equals(key)) {
                    sneakyThrow(new IOException("checked-io"));
                }
                return super.get(key);
            }
        };
        when(responseSpec.bodyToMono(Map.class)).thenReturn(Mono.just(throwingOnChoices));

        SummarizerConfig cfg = SummarizerConfig.builder()
                .id(UUID.randomUUID()).name("r").baseUrl("http://h").modelId("m").build();

        assertThatThrownBy(() -> new OpenAiCompatibleClient(webClient).summarize(cfg, "p"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("checked-io")
                .satisfies(t -> assertThat(t.getCause()).isInstanceOf(IOException.class));
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void sneakyThrow(Throwable e) throws E {
        throw (E) e;
    }

    @Test
    @SuppressWarnings("unchecked")
    void summarize_emptyResponse_nullChoices_badShape_nullContent_reactiveError() {
        WebClient webClient = mock(WebClient.class);
        WebClient.RequestBodyUriSpec post = mock(WebClient.RequestBodyUriSpec.class);
        WebClient.RequestBodySpec bodySpec = mock(WebClient.RequestBodySpec.class);
        WebClient.RequestHeadersSpec<?> headersSpec = mock(WebClient.RequestHeadersSpec.class);
        WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);

        when(webClient.post()).thenReturn(post);
        when(post.uri(anyString())).thenReturn(bodySpec);
        when(bodySpec.contentType(MediaType.APPLICATION_JSON)).thenReturn(bodySpec);
        when(bodySpec.bodyValue(any())).thenReturn((WebClient.RequestHeadersSpec) headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);

        OpenAiCompatibleClient client = new OpenAiCompatibleClient(webClient);
        SummarizerConfig cfg = SummarizerConfig.builder()
                .id(UUID.randomUUID()).name("r").baseUrl("http://h").modelId("m").build();

        when(responseSpec.bodyToMono(Map.class)).thenReturn(Mono.empty());
        assertThatThrownBy(() -> client.summarize(cfg, "p"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Empty response");

        when(responseSpec.bodyToMono(Map.class)).thenReturn(Mono.just(Map.of("choices", List.of())));
        assertThatThrownBy(() -> client.summarize(cfg, "p"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Unexpected response structure");

        when(responseSpec.bodyToMono(Map.class)).thenReturn(Mono.just(Map.of("choices", List.of("x"))));
        assertThatThrownBy(() -> client.summarize(cfg, "p"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Unexpected response structure");

        when(responseSpec.bodyToMono(Map.class)).thenReturn(Mono.just(
                Map.of("choices", List.of(Map.of("message", "not-map")))
        ));
        assertThatThrownBy(() -> client.summarize(cfg, "p"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Unexpected response structure");

        Map<String, Object> messageWithNullContent = new HashMap<>();
        messageWithNullContent.put("content", null);
        Map<String, Object> choiceMap = new HashMap<>();
        choiceMap.put("message", messageWithNullContent);
        when(responseSpec.bodyToMono(Map.class)).thenReturn(Mono.just(
                Map.of("choices", List.of(choiceMap))
        ));
        assertThatThrownBy(() -> client.summarize(cfg, "p"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Unexpected response structure");

        when(responseSpec.bodyToMono(Map.class)).thenReturn(Mono.error(new RuntimeException("io")));
        assertThatThrownBy(() -> client.summarize(cfg, "p"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("io");
    }
}
