package com.loadtest.metrics.service;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

public final class MetricsConfigPlaceholderReplacer {

    private static final Pattern TEST_START = Pattern.compile(Pattern.quote("{testStartTime}"));
    private static final Pattern TEST_END = Pattern.compile(Pattern.quote("{testEndTime}"));
    private static final Pattern TEST_START_MS = Pattern.compile(Pattern.quote("{testStartTimeMs}"));
    private static final Pattern TEST_END_MS = Pattern.compile(Pattern.quote("{testEndTimeMs}"));
    private static final Pattern TEST_START_ISO = Pattern.compile(Pattern.quote("{testStartTimeIso}"));
    private static final Pattern TEST_END_ISO = Pattern.compile(Pattern.quote("{testEndTimeIso}"));

    private MetricsConfigPlaceholderReplacer() {
    }

    public static String replace(String text, long startEpochMs, long endEpochMs) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        long startSec = startEpochMs / 1000L;
        long endSec = endEpochMs / 1000L;
        String startIso = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(startEpochMs));
        String endIso = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(endEpochMs));

        String out = text;
        out = TEST_START.matcher(out).replaceAll(String.valueOf(startSec));
        out = TEST_END.matcher(out).replaceAll(String.valueOf(endSec));
        out = TEST_START_MS.matcher(out).replaceAll(String.valueOf(startEpochMs));
        out = TEST_END_MS.matcher(out).replaceAll(String.valueOf(endEpochMs));
        out = TEST_START_ISO.matcher(out).replaceAll(startIso);
        out = TEST_END_ISO.matcher(out).replaceAll(endIso);
        return out;
    }
}
