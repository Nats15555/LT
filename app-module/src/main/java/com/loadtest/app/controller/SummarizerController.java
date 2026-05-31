package com.loadtest.app.controller;

import com.loadtest.app.dto.CreateSummarizerRequest;
import com.loadtest.app.dto.SummarizerModelDto;
import com.loadtest.app.dto.UpdateSummarizerRequest;
import com.loadtest.app.service.SummarizerService;
import com.loadtest.app.util.ApiJsonKeys;
import com.loadtest.app.util.ApiMessages;
import com.loadtest.app.util.ResponseHelper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/loadtest/summarizers")
@RequiredArgsConstructor
public class SummarizerController {

    private final SummarizerService summarizerService;

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody CreateSummarizerRequest request) {
        try {
            SummarizerModelDto dto = summarizerService.create(request);
            return ResponseHelper.buildSuccessResponse(HttpStatus.CREATED, ApiMessages.Summarizers.CREATED, Map.of(ApiJsonKeys.DATA, dto));
        } catch (IllegalArgumentException e) {
            return ResponseHelper.buildErrorResponse(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (RuntimeException e) {
            log.error("Failed to create summarizer", e);
            return ResponseHelper.buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                    ApiMessages.Summarizers.FAILED_CREATE_PREFIX + e.getMessage());
        }
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> getAll(@RequestParam(required = false) Boolean enabled) {
        try {
            List<SummarizerModelDto> list = enabled != null && enabled
                    ? summarizerService.getEnabled()
                    : summarizerService.getAll();
            return ResponseHelper.buildSuccessResponse(HttpStatus.OK, ApiMessages.Summarizers.LIST_RETRIEVED, Map.of(ApiJsonKeys.DATA, list));
        } catch (RuntimeException e) {
            log.error("Failed to get summarizers", e);
            return ResponseHelper.buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                    ApiMessages.Summarizers.FAILED_LIST_PREFIX + e.getMessage());
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getById(@PathVariable UUID id) {
        try {
            SummarizerModelDto dto = summarizerService.getById(id);
            return ResponseHelper.buildSuccessResponse(HttpStatus.OK, ApiMessages.Summarizers.RETRIEVED, Map.of(ApiJsonKeys.DATA, dto));
        } catch (IllegalArgumentException e) {
            return ResponseHelper.buildErrorResponse(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (RuntimeException e) {
            log.error("Failed to get summarizer by id: {}", id, e);
            return ResponseHelper.buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                    ApiMessages.Summarizers.FAILED_GET_PREFIX + e.getMessage());
        }
    }

    @GetMapping("/name/{name}")
    public ResponseEntity<Map<String, Object>> getByName(@PathVariable String name) {
        try {
            SummarizerModelDto dto = summarizerService.getByName(name);
            return ResponseHelper.buildSuccessResponse(HttpStatus.OK, ApiMessages.Summarizers.RETRIEVED, Map.of(ApiJsonKeys.DATA, dto));
        } catch (IllegalArgumentException e) {
            return ResponseHelper.buildErrorResponse(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (RuntimeException e) {
            log.error("Failed to get summarizer by name: {}", name, e);
            return ResponseHelper.buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                    ApiMessages.Summarizers.FAILED_GET_PREFIX + e.getMessage());
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable UUID id, @Valid @RequestBody UpdateSummarizerRequest request) {
        try {
            SummarizerModelDto dto = summarizerService.update(id, request);
            return ResponseHelper.buildSuccessResponse(HttpStatus.OK, ApiMessages.Summarizers.UPDATED, Map.of(ApiJsonKeys.DATA, dto));
        } catch (IllegalArgumentException e) {
            return ResponseHelper.buildErrorResponse(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (RuntimeException e) {
            log.error("Failed to update summarizer: {}", id, e);
            return ResponseHelper.buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                    ApiMessages.Summarizers.FAILED_UPDATE_PREFIX + e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable UUID id) {
        try {
            summarizerService.delete(id);
            return ResponseHelper.buildSuccessResponse(HttpStatus.OK, ApiMessages.Summarizers.DELETED, null);
        } catch (IllegalArgumentException e) {
            return ResponseHelper.buildErrorResponse(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (RuntimeException e) {
            log.error("Failed to delete summarizer", e);
            return ResponseHelper.buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                    ApiMessages.Summarizers.FAILED_DELETE_PREFIX + e.getMessage());
        }
    }
}
