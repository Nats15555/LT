package com.loadtest.app.util;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;

public class ResponseHelper {

    public static ResponseEntity<Map<String, String>> buildErrorResponse(HttpStatus status, String message) {
        Map<String, String> error = new HashMap<>();
        error.put("status", "error");
        error.put("message", message);
        return ResponseEntity.status(status).body(error);
    }

    public static ResponseEntity<Map<String, Object>> buildSuccessResponse(HttpStatus status, String message, Map<String, Object> data) {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "success");
        response.put("message", message);
        if (data != null) {
            response.putAll(data);
        }
        return ResponseEntity.status(status).body(response);
    }
}


