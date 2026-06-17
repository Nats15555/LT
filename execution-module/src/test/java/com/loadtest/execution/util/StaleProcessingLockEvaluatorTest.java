package com.loadtest.execution.util;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class StaleProcessingLockEvaluatorTest {

    @Test
    void isStale_falseWhenWithinDeadline() {
        OffsetDateTime now = OffsetDateTime.parse("2026-05-31T12:00:00Z");
        OffsetDateTime lockedAt = now.minusSeconds(300);
        assertThat(StaleProcessingLockEvaluator.isStale(lockedAt, 600, now, 600)).isFalse();
    }

    @Test
    void isStale_trueAfterDurationPlusGrace() {
        OffsetDateTime now = OffsetDateTime.parse("2026-05-31T12:00:00Z");
        OffsetDateTime lockedAt = now.minusSeconds(601 + 600);
        assertThat(StaleProcessingLockEvaluator.isStale(lockedAt, 601, now, 600)).isTrue();
    }

    @Test
    void isStale_falseWhenLockedAtNull() {
        assertThat(StaleProcessingLockEvaluator.isStale(null, 60, OffsetDateTime.now(), 600)).isFalse();
    }
}
