package com.loadtest.metrics.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MetricsConfigPlaceholderReplacerTest {

    @Test
    void replace_handlesNullEmptyAndAllPlaceholders() {
        assertThat(MetricsConfigPlaceholderReplacer.replace(null, 1L, 2L)).isNull();
        assertThat(MetricsConfigPlaceholderReplacer.replace("", 1L, 2L)).isEmpty();

        String in = "s={testStartTime},e={testEndTime},sm={testStartTimeMs},em={testEndTimeMs},si={testStartTimeIso},ei={testEndTimeIso}";
        String out = MetricsConfigPlaceholderReplacer.replace(in, 1_000L, 2_000L);
        assertThat(out).contains("s=1");
        assertThat(out).contains("e=2");
        assertThat(out).contains("sm=1000");
        assertThat(out).contains("em=2000");
        assertThat(out).contains("si=1970-01-01T00:00:01Z");
        assertThat(out).contains("ei=1970-01-01T00:00:02Z");
    }
}

