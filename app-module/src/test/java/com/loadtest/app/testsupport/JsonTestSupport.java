package com.loadtest.app.testsupport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

public final class JsonTestSupport {

    private JsonTestSupport() {
    }

    public static JsonNode readTree(ObjectMapper mapper, String json) {
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException ex) {
            throw new AssertionError("Invalid test JSON", ex);
        }
    }

    public static String writeValueAsString(ObjectMapper mapper, Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new AssertionError("Failed to serialize test JSON", ex);
        }
    }

    public static void stubWriteValueAsStringFailure(ObjectMapper mapper, JsonProcessingException failure) {
        try {
            when(mapper.writeValueAsString(any())).thenThrow(failure);
        } catch (JsonProcessingException ex) {
            throw new AssertionError("Unexpected JsonProcessingException from mocked ObjectMapper", ex);
        }
    }
}
