package com.loadtest.app.util;

import java.util.Map;

public final class RequestBodyHelper {

    private RequestBodyHelper() {
    }

    public static String extractCustomPrompt(Map<String, ?> body) {
        if (body == null) {
            return null;
        }
        Object raw = body.get("customPrompt");
        if (raw == null) {
            raw = body.get("prompt");
        }
        if (raw == null) {
            return null;
        }
        String s = String.valueOf(raw).trim();
        return s.isEmpty() ? null : s;
    }
}
