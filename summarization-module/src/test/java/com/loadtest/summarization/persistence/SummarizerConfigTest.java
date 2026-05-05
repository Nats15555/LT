package com.loadtest.summarization.persistence;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SummarizerConfigTest {

    @Test
    void builderRoundTrip() {
        UUID id = UUID.randomUUID();
        SummarizerConfig c = SummarizerConfig.builder()
                .id(id)
                .name("n")
                .provider("OPENAI")
                .baseUrl("http://localhost:4000")
                .modelId("m")
                .apiKeyEnvVar("K")
                .apiKeyResolved("secret")
                .build();
        assertThat(c.getId()).isEqualTo(id);
        assertThat(c.getName()).isEqualTo("n");
        assertThat(c.getProvider()).isEqualTo("OPENAI");
        assertThat(c.getBaseUrl()).contains("4000");
        assertThat(c.getModelId()).isEqualTo("m");
        assertThat(c.getApiKeyEnvVar()).isEqualTo("K");
        assertThat(c.getApiKeyResolved()).isEqualTo("secret");
    }

    @Test
    void noArgsAndSetters() {
        SummarizerConfig c = new SummarizerConfig();
        c.setName("x");
        assertThat(c.getName()).isEqualTo("x");
    }
}
