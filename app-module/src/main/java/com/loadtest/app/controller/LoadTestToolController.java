package com.loadtest.app.controller;

import com.loadtest.app.dto.CreateLoadTestToolRequest;
import com.loadtest.app.dto.LoadTestToolDto;
import com.loadtest.app.dto.UpdateLoadTestToolRequest;
import com.loadtest.app.service.LoadTestToolService;
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
@RequestMapping("/api/v1/loadtest/tools")
@RequiredArgsConstructor
public class LoadTestToolController {

    private final LoadTestToolService toolService;

    @PostMapping
    public ResponseEntity<Map<String, Object>> createTool(@Valid @RequestBody CreateLoadTestToolRequest request) {
        try {
            LoadTestToolDto tool = toolService.createTool(request);
            return ResponseHelper.buildSuccessResponse(HttpStatus.CREATED, ApiMessages.Tools.CREATED, Map.of(ApiJsonKeys.DATA, tool));
        } catch (IllegalArgumentException e) {
            return ResponseHelper.buildErrorResponse(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (RuntimeException e) {
            log.error("Failed to create tool", e);
            return ResponseHelper.buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                    ApiMessages.Tools.FAILED_CREATE_PREFIX + e.getMessage());
        }
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> getAllTools(@RequestParam(required = false) Boolean enabled) {
        try {
            List<LoadTestToolDto> tools;
            if (enabled != null && enabled) {
                tools = toolService.getEnabledTools();
            } else {
                tools = toolService.getAllTools();
            }
            return ResponseHelper.buildSuccessResponse(HttpStatus.OK, ApiMessages.Tools.LIST_RETRIEVED, Map.of(ApiJsonKeys.DATA, tools));
        } catch (RuntimeException e) {
            log.error("Failed to get tools", e);
            return ResponseHelper.buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                    ApiMessages.Tools.FAILED_LIST_PREFIX + e.getMessage());
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getToolById(@PathVariable UUID id) {
        try {
            LoadTestToolDto tool = toolService.getToolById(id);
            return ResponseHelper.buildSuccessResponse(HttpStatus.OK, ApiMessages.Tools.RETRIEVED, Map.of(ApiJsonKeys.DATA, tool));
        } catch (IllegalArgumentException e) {
            return ResponseHelper.buildErrorResponse(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (RuntimeException e) {
            log.error("Failed to get tool by id: {}", id, e);
            return ResponseHelper.buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                    ApiMessages.Tools.FAILED_GET_PREFIX + e.getMessage());
        }
    }

    @GetMapping("/name/{name}")
    public ResponseEntity<Map<String, Object>> getToolByName(@PathVariable String name) {
        try {
            LoadTestToolDto tool = toolService.getToolByName(name);
            return ResponseHelper.buildSuccessResponse(HttpStatus.OK, ApiMessages.Tools.RETRIEVED, Map.of(ApiJsonKeys.DATA, tool));
        } catch (IllegalArgumentException e) {
            return ResponseHelper.buildErrorResponse(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (RuntimeException e) {
            log.error("Failed to get tool by name: {}", name, e);
            return ResponseHelper.buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                    ApiMessages.Tools.FAILED_GET_PREFIX + e.getMessage());
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> updateTool(@PathVariable UUID id,
                                        @Valid @RequestBody UpdateLoadTestToolRequest request) {
        try {
            LoadTestToolDto tool = toolService.updateTool(id, request);
            return ResponseHelper.buildSuccessResponse(HttpStatus.OK, ApiMessages.Tools.UPDATED, Map.of(ApiJsonKeys.DATA, tool));
        } catch (IllegalArgumentException e) {
            return ResponseHelper.buildErrorResponse(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (RuntimeException e) {
            log.error("Failed to update tool: {}", id, e);
            return ResponseHelper.buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                    ApiMessages.Tools.FAILED_UPDATE_PREFIX + e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteTool(@PathVariable UUID id) {
        try {
            toolService.deleteTool(id);
            return ResponseHelper.buildSuccessResponse(HttpStatus.OK, ApiMessages.Tools.DELETED, null);
        } catch (IllegalArgumentException e) {
            return ResponseHelper.buildErrorResponse(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (RuntimeException e) {
            log.error("Failed to delete tool: {}", id, e);
            return ResponseHelper.buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                    ApiMessages.Tools.FAILED_DELETE_PREFIX + e.getMessage());
        }
    }
}
