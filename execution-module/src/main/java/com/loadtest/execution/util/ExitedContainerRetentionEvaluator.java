package com.loadtest.execution.util;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;

public final class ExitedContainerRetentionEvaluator {

    private static final String DOCKER_ZERO_TIME_PREFIX = "0001-01-01";

    private ExitedContainerRetentionEvaluator() {
    }

    public static boolean shouldRemove(OffsetDateTime finishedAt, OffsetDateTime now, long retentionHours) {
        if (finishedAt == null || now == null || retentionHours < 0) {
            return false;
        }
        return finishedAt.isBefore(now.minusHours(retentionHours));
    }

    public static Optional<OffsetDateTime> parseDockerFinishedAt(String raw) {
        if (raw == null || raw.isBlank() || raw.startsWith(DOCKER_ZERO_TIME_PREFIX)) {
            return Optional.empty();
        }
        try {
            return Optional.of(OffsetDateTime.parse(raw));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
