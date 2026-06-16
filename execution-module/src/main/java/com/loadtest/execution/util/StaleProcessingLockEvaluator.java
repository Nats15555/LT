package com.loadtest.execution.util;

import java.time.OffsetDateTime;

public final class StaleProcessingLockEvaluator {

    private static final long DEFAULT_EXPECTED_DURATION_SECONDS = 60;

    private StaleProcessingLockEvaluator() {
    }

    public static boolean isStale(
            OffsetDateTime lockedAt,
            Integer expectedDurationSeconds,
            OffsetDateTime now,
            long graceSeconds) {
        if (lockedAt == null || graceSeconds < 0) {
            return false;
        }
        long durationSeconds = expectedDurationSeconds != null && expectedDurationSeconds > 0
                ? expectedDurationSeconds
                : DEFAULT_EXPECTED_DURATION_SECONDS;
        OffsetDateTime deadline = lockedAt.plusSeconds(durationSeconds + graceSeconds);
        return !now.isBefore(deadline);
    }
}
