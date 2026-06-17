package com.loadtest.execution.util;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class ExitedContainerRetentionEvaluatorTest {

    @Test
    void shouldRemove_whenFinishedOlderThanRetention() {
        OffsetDateTime now = OffsetDateTime.parse("2026-06-01T12:00:00Z");
        OffsetDateTime finishedAt = OffsetDateTime.parse("2026-05-29T11:59:59Z");

        assertThat(ExitedContainerRetentionEvaluator.shouldRemove(finishedAt, now, 48)).isTrue();
    }

    @Test
    void shouldNotRemove_whenFinishedWithinRetention() {
        OffsetDateTime now = OffsetDateTime.parse("2026-06-01T12:00:00Z");
        OffsetDateTime finishedAt = OffsetDateTime.parse("2026-05-31T12:00:01Z");

        assertThat(ExitedContainerRetentionEvaluator.shouldRemove(finishedAt, now, 48)).isFalse();
    }

    @Test
    void parseDockerFinishedAt_skipsZeroTime() {
        assertThat(ExitedContainerRetentionEvaluator.parseDockerFinishedAt("0001-01-01T00:00:00Z")).isEmpty();
        assertThat(ExitedContainerRetentionEvaluator.parseDockerFinishedAt(null)).isEmpty();
    }

    @Test
    void parseDockerFinishedAt_parsesIsoTimestamp() {
        assertThat(ExitedContainerRetentionEvaluator.parseDockerFinishedAt("2026-05-31T10:15:30.123456789Z"))
                .contains(OffsetDateTime.parse("2026-05-31T10:15:30.123456789Z"));
    }
}
