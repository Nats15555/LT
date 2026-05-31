package com.loadtest.app.config;

import com.loadtest.app.util.ApiMessages;
import com.loadtest.app.util.ResponseHelper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;

@RestControllerAdvice
public class UploadExceptionHandler {

    @Value("${loadtest.upload.max-scenario-file-size-bytes:10485760}")
    private long maxScenarioFileSizeBytes;

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxUploadSize() {
        long limit = maxScenarioFileSizeBytes;
        return ResponseHelper.buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                ApiMessages.Upload.fileTooLarge(limit, limit));
    }
}
