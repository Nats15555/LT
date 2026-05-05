package com.loadtest.app.controller;

import com.loadtest.app.dto.CreateLoadTestToolRequest;
import com.loadtest.app.dto.LoadTestToolDto;
import com.loadtest.app.dto.UpdateLoadTestToolRequest;
import com.loadtest.app.service.LoadTestToolService;
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
@RequestMapping("/api/v1/loadtest/tools")
@RequiredArgsConstructor
public class LoadTestToolController {

    private final LoadTestToolService toolService;

    @PostMapping
    public ResponseEntity<?> createTool(@Valid @RequestBody CreateLoadTestToolRequest request) {
        try {
            LoadTestToolDto tool = toolService.createTool(request);
            Map<String, Object> data = new HashMap<>();
            data.put("data", tool);
            return ResponseHelper.buildSuccessResponse(HttpStatus.CREATED, "Tool created successfully", data);
        } catch (IllegalArgumentException e) {
            return ResponseHelper.buildErrorResponse(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            log.error("Failed to create tool", e);
            return ResponseHelper.buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, 
                    "Failed to create tool: " + e.getMessage());
        }
    }

    @GetMapping
    public ResponseEntity<?> getAllTools(@RequestParam(required = false) Boolean enabled) {
        try {
            List<LoadTestToolDto> tools;
            if (enabled != null && enabled) {
                tools = toolService.getEnabledTools();
            } else {
                tools = toolService.getAllTools();
            }
            Map<String, Object> data = new HashMap<>();
            data.put("data", tools);
            return ResponseHelper.buildSuccessResponse(HttpStatus.OK, "Tools retrieved successfully", data);
        } catch (Exception e) {
            log.error("Failed to get tools", e);
            return ResponseHelper.buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, 
                    "Failed to get tools: " + e.getMessage());
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getToolById(@PathVariable UUID id) {
        try {
            LoadTestToolDto tool = toolService.getToolById(id);
            Map<String, Object> data = new HashMap<>();
            data.put("data", tool);
            return ResponseHelper.buildSuccessResponse(HttpStatus.OK, "Tool retrieved successfully", data);
        } catch (IllegalArgumentException e) {
            return ResponseHelper.buildErrorResponse(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (Exception e) {
            log.error("Failed to get tool by id: {}", id, e);
            return ResponseHelper.buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, 
                    "Failed to get tool: " + e.getMessage());
        }
    }

    @GetMapping("/name/{name}")
    public ResponseEntity<?> getToolByName(@PathVariable String name) {
        try {
            LoadTestToolDto tool = toolService.getToolByName(name);
            Map<String, Object> data = new HashMap<>();
            data.put("data", tool);
            return ResponseHelper.buildSuccessResponse(HttpStatus.OK, "Tool retrieved successfully", data);
        } catch (IllegalArgumentException e) {
            return ResponseHelper.buildErrorResponse(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (Exception e) {
            log.error("Failed to get tool by name: {}", name, e);
            return ResponseHelper.buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, 
                    "Failed to get tool: " + e.getMessage());
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateTool(@PathVariable UUID id, 
                                        @Valid @RequestBody UpdateLoadTestToolRequest request) {
        try {
            LoadTestToolDto tool = toolService.updateTool(id, request);
            Map<String, Object> data = new HashMap<>();
            data.put("data", tool);
            return ResponseHelper.buildSuccessResponse(HttpStatus.OK, "Tool updated successfully", data);
        } catch (IllegalArgumentException e) {
            return ResponseHelper.buildErrorResponse(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (Exception e) {
            log.error("Failed to update tool: {}", id, e);
            return ResponseHelper.buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, 
                    "Failed to update tool: " + e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteTool(@PathVariable UUID id) {
        try {
            toolService.deleteTool(id);
            return ResponseHelper.buildSuccessResponse(HttpStatus.OK, "Tool deleted successfully", null);
        } catch (IllegalArgumentException e) {
            return ResponseHelper.buildErrorResponse(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (Exception e) {
            log.error("Failed to delete tool: {}", id, e);
            return ResponseHelper.buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, 
                    "Failed to delete tool: " + e.getMessage());
        }
    }
}
