package com.loadtest.app.controller;

import com.loadtest.app.dto.CreateSummarizerRequest;
import com.loadtest.app.dto.SummarizerModelDto;
import com.loadtest.app.dto.UpdateSummarizerRequest;
import com.loadtest.app.service.SummarizerService;
import com.loadtest.app.util.ResponseHelper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
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
    public ResponseEntity<?> create(@Valid @RequestBody CreateSummarizerRequest request) {
        try {
            SummarizerModelDto dto = summarizerService.create(request);
            Map<String, Object> data = new HashMap<>();
            data.put("data", dto);
            return ResponseHelper.buildSuccessResponse(HttpStatus.CREATED, "Summarizer created successfully", data);
        } catch (IllegalArgumentException e) {
            return ResponseHelper.buildErrorResponse(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            log.error("Failed to create summarizer", e);
            return ResponseHelper.buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to create summarizer: " + e.getMessage());
        }
    }

    @GetMapping
    public ResponseEntity<?> getAll(@RequestParam(required = false) Boolean enabled) {
        try {
            List<SummarizerModelDto> list = enabled != null && enabled
                    ? summarizerService.getEnabled()
                    : summarizerService.getAll();
            Map<String, Object> data = new HashMap<>();
            data.put("data", list);
            return ResponseHelper.buildSuccessResponse(HttpStatus.OK, "Summarizers retrieved successfully", data);
        } catch (Exception e) {
            log.error("Failed to get summarizers", e);
            return ResponseHelper.buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to get summarizers: " + e.getMessage());
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable UUID id) {
        try {
            SummarizerModelDto dto = summarizerService.getById(id);
            Map<String, Object> data = new HashMap<>();
            data.put("data", dto);
            return ResponseHelper.buildSuccessResponse(HttpStatus.OK, "Summarizer retrieved successfully", data);
        } catch (IllegalArgumentException e) {
            return ResponseHelper.buildErrorResponse(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (Exception e) {
            log.error("Failed to get summarizer by id: {}", id, e);
            return ResponseHelper.buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to get summarizer: " + e.getMessage());
        }
    }

    @GetMapping("/name/{name}")
    public ResponseEntity<?> getByName(@PathVariable String name) {
        try {
            SummarizerModelDto dto = summarizerService.getByName(name);
            Map<String, Object> data = new HashMap<>();
            data.put("data", dto);
            return ResponseHelper.buildSuccessResponse(HttpStatus.OK, "Summarizer retrieved successfully", data);
        } catch (IllegalArgumentException e) {
            return ResponseHelper.buildErrorResponse(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (Exception e) {
            log.error("Failed to get summarizer by name: {}", name, e);
            return ResponseHelper.buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to get summarizer: " + e.getMessage());
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable UUID id, @Valid @RequestBody UpdateSummarizerRequest request) {
        try {
            SummarizerModelDto dto = summarizerService.update(id, request);
            Map<String, Object> data = new HashMap<>();
            data.put("data", dto);
            return ResponseHelper.buildSuccessResponse(HttpStatus.OK, "Summarizer updated successfully", data);
        } catch (IllegalArgumentException e) {
            return ResponseHelper.buildErrorResponse(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (Exception e) {
            log.error("Failed to update summarizer: {}", id, e);
            return ResponseHelper.buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to update summarizer: " + e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable UUID id) {
        try {
            summarizerService.delete(id);
            return ResponseHelper.buildSuccessResponse(HttpStatus.OK, "Summarizer deleted successfully", null);
        } catch (IllegalArgumentException e) {
            return ResponseHelper.buildErrorResponse(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (Exception e) {
            log.error("Failed to delete summarizer: {}", id, e);
            return ResponseHelper.buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to delete summarizer: " + e.getMessage());
        }
    }
}
