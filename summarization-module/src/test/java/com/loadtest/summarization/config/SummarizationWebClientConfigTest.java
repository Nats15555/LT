package com.loadtest.summarization.config;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;

class SummarizationWebClientConfigTest {

    @Test
    void summarizationLlmWebClient_isCreated_withDefaultsAndMinMemory() {
        SummarizationWebClientConfig cfg = new SummarizationWebClientConfig();
        WebClient client = cfg.summarizationLlmWebClient(1000, 1, 0);
        assertThat(client).isNotNull();
    }
}
