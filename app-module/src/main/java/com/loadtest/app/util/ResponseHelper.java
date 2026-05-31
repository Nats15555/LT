package com.loadtest.app.util;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;

public final class ResponseHelper {

    private ResponseHelper() {
    }

    public static ResponseEntity<Map<String, Object>> buildErrorResponse(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(simpleErrorBody(message));
    }

    public static ResponseEntity<Map<String, Object>> buildSuccessResponse(
            HttpStatus status, String message, Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return ResponseEntity.status(status).body(simpleSuccessBody(message));
        }
        Map<String, Object> response = new HashMap<>(simpleSuccessBody(message));
        response.putAll(data);
        return ResponseEntity.status(status).body(response);
    }

    public static Map<String, Object> simpleSuccessBody(String message) {
        return Map.of(ApiJsonKeys.STATUS, ApiResponseValues.STATUS_SUCCESS, ApiJsonKeys.MESSAGE, message);
    }

    public static Map<String, String> messageBody(String message) {
        return Map.of(ApiJsonKeys.MESSAGE, message);
    }

    public static Map<String, Object> simpleErrorBody(String message) {
        return Map.of(ApiJsonKeys.STATUS, ApiResponseValues.STATUS_ERROR, ApiJsonKeys.MESSAGE, message);
    }
}
