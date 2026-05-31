package com.loadtest.app.util;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ResponseHelperTest {

    @Test
    void buildErrorResponse_shapesPayload() {
        ResponseEntity<Map<String, Object>> r =
                ResponseHelper.buildErrorResponse(HttpStatus.BAD_REQUEST, "bad");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).containsEntry(ApiJsonKeys.STATUS, ApiResponseValues.STATUS_ERROR).containsEntry(ApiJsonKeys.MESSAGE, "bad");
    }

    @Test
    void buildSuccessResponse_mergesDataWhenPresent() {
        ResponseEntity<Map<String, Object>> r = ResponseHelper.buildSuccessResponse(
                HttpStatus.CREATED, "ok", Map.of("id", "1"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody()).containsEntry(ApiJsonKeys.STATUS, ApiResponseValues.STATUS_SUCCESS).containsEntry(ApiJsonKeys.MESSAGE, "ok").containsEntry("id", "1");
    }

    @Test
    void buildSuccessResponse_allowsNullData() {
        ResponseEntity<Map<String, Object>> r =
                ResponseHelper.buildSuccessResponse(HttpStatus.OK, "ok", null);
        assertThat(r.getBody()).containsEntry(ApiJsonKeys.STATUS, ApiResponseValues.STATUS_SUCCESS).doesNotContainKey("id");
    }
}
