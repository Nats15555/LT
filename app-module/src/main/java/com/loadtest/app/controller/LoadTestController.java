package com.loadtest.app.controller;

import com.loadtest.app.dto.LoadTestToolDto;
import com.loadtest.app.dto.TestTaskMessage;
import com.loadtest.app.dto.SummarizerModelDto;
import com.loadtest.app.service.DockerExecutionProfileService;
import com.loadtest.app.service.LoadTestToolService;
import com.loadtest.app.service.SummarizerService;
import com.loadtest.app.service.TestQueueService;
import com.loadtest.app.util.FileValidationHelper;
import com.loadtest.app.util.MetricsConfigParser;
import com.loadtest.app.util.ResponseHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/loadtest")
public class LoadTestController {
    
    private final TestQueueService testQueueService;
    private final MetricsConfigParser metricsConfigParser;
    private final LoadTestToolService loadTestToolService;
    private final SummarizerService summarizerService;
    private final DockerExecutionProfileService dockerExecutionProfileService;

    public LoadTestController(TestQueueService testQueueService,
                             MetricsConfigParser metricsConfigParser,
                             LoadTestToolService loadTestToolService,
                             SummarizerService summarizerService,
                             DockerExecutionProfileService dockerExecutionProfileService) {
        this.testQueueService = testQueueService;
        this.metricsConfigParser = metricsConfigParser;
        this.loadTestToolService = loadTestToolService;
        this.summarizerService = summarizerService;
        this.dockerExecutionProfileService = dockerExecutionProfileService;
    }

    @GetMapping(value = "/metrics-config-schema", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Resource> getMetricsConfigSchema() {
        return ResponseEntity.ok(new ClassPathResource("schemas/metrics-config.schema.json"));
    }

    @PostMapping("/upload")
    public ResponseEntity<Map<String, String>> uploadTestFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam("tool") String tool,
            @RequestParam("command") String command,
            @RequestParam("expectedDurationSeconds") Integer expectedDurationSeconds,
            @RequestParam(value = "metricsConfig", required = false) String metricsConfig,
            @RequestParam(value = "summarizer", required = false) String summarizer,
            @RequestParam(value = "dockerExecutionProfileId", required = false) String dockerExecutionProfileId) {
        
        log.info("Uploading test file: {}, tool: {}", file.getOriginalFilename(), tool);
        
        try {
            if (command == null || command.isBlank()) {
                return ResponseHelper.buildErrorResponse(HttpStatus.BAD_REQUEST, 
                        "Command is required");
            }
            if (expectedDurationSeconds == null || expectedDurationSeconds < 1) {
                return ResponseHelper.buildErrorResponse(HttpStatus.BAD_REQUEST, 
                        "expectedDurationSeconds is required and must be at least 1");
            }

            LoadTestToolDto toolDto;
            try {
                toolDto = loadTestToolService.getToolByName(tool.toUpperCase());
            } catch (IllegalArgumentException e) {
                return ResponseHelper.buildErrorResponse(HttpStatus.BAD_REQUEST, 
                        "Tool '" + tool + "' not found in database. Please check available tools via GET /api/v1/loadtest/tools");
            }

            if (!toolDto.getEnabled()) {
                return ResponseHelper.buildErrorResponse(HttpStatus.BAD_REQUEST, 
                        "Tool '" + tool + "' is disabled. Please enable it first or use another tool.");
            }

            if (summarizer != null && !summarizer.isBlank()) {
                try {
                    SummarizerModelDto dto = summarizerService.getByName(summarizer.trim());
                    if (dto.getEnabled() == null || !dto.getEnabled()) {
                        return ResponseHelper.buildErrorResponse(HttpStatus.BAD_REQUEST,
                                "Summarizer '" + summarizer + "' is disabled. Enable it or use another.");
                    }
                    if ("EXTERNAL".equalsIgnoreCase(dto.getProvider())) {
                        String bu = dto.getBaseUrl();
                        if (bu == null || bu.isBlank()) {
                            return ResponseHelper.buildErrorResponse(HttpStatus.BAD_REQUEST,
                                    "Маршрут EXTERNAL требует полный URL приёма пакета (baseUrl в записи summarizer_models).");
                        }
                        String t = bu.trim();
                        if (!t.matches("(?i)https?://.*")) {
                            return ResponseHelper.buildErrorResponse(HttpStatus.BAD_REQUEST,
                                    "baseUrl для EXTERNAL должен начинаться с http:// или https://");
                        }
                    }
                } catch (IllegalArgumentException e) {
                    return ResponseHelper.buildErrorResponse(HttpStatus.BAD_REQUEST,
                            "Summarizer '" + summarizer + "' not found. Use GET /api/v1/loadtest/summarizers?enabled=true");
                }
            }

            String fileValidationError = FileValidationHelper.validateFile(file);
            if (fileValidationError != null) {
                return ResponseHelper.buildErrorResponse(HttpStatus.BAD_REQUEST, fileValidationError);
            }
            
            String fileName = file.getOriginalFilename();

            String fileExtension = FileValidationHelper.getFileExtension(fileName);
            if (fileExtension.isEmpty()) {
                return ResponseHelper.buildErrorResponse(HttpStatus.BAD_REQUEST, 
                        String.format("File must have an extension. Tool '%s' supports extensions: %s", 
                                toolDto.getName(), 
                                String.join(", ", toolDto.getFileExtensions())));
            }

            boolean extensionMatches = toolDto.getFileExtensions() != null && 
                    toolDto.getFileExtensions().stream()
                            .anyMatch(ext -> ext.replace(".", "").equalsIgnoreCase(fileExtension));
            
            if (!extensionMatches) {
                return ResponseHelper.buildErrorResponse(HttpStatus.BAD_REQUEST, 
                        String.format("File extension mismatch! Tool '%s' supports extensions: %s, but got '.%s'. " +
                                "Please ensure the file extension matches one of the supported extensions.", 
                                toolDto.getName(), 
                                String.join(", ", toolDto.getFileExtensions()), fileExtension));
            }
            
            log.info("File extension validated: .{} for tool {}", fileExtension, toolDto.getName());

            byte[] fileBytes = file.getBytes();
            String fileContentBase64 = Base64.getEncoder().encodeToString(fileBytes);
            
            log.info("File prepared for transmission: {} ({} bytes, Base64: {} chars)", 
                    fileName, fileBytes.length, fileContentBase64.length());

            TestTaskMessage.MetricsConfig metricsConfigObj = null;
            if (metricsConfig != null && !metricsConfig.trim().isEmpty()) {
                try {
                    metricsConfigObj = metricsConfigParser.parseMetricsConfigRequests(metricsConfig);
                    log.info("Metrics collection configured for task: {} requests, delay: {}s",
                            metricsConfigObj.getRequests() != null ? metricsConfigObj.getRequests().size() : 0,
                            metricsConfigObj.getDelaySeconds());
                } catch (Exception e) {
                    log.error("Failed to parse metrics configuration", e);
                    return ResponseHelper.buildErrorResponse(HttpStatus.BAD_REQUEST,
                            "Invalid metrics configuration: " + e.getMessage());
                }
            }
            
            UUID resolvedProfileId = dockerExecutionProfileService.resolveProfileIdForUpload(dockerExecutionProfileId);

            String taskId = testQueueService.enqueueTest(
                    tool.toUpperCase(),
                    fileName,
                    fileContentBase64,
                    command,
                    expectedDurationSeconds,
                    metricsConfigObj,
                    metricsConfig,
                    summarizer != null && !summarizer.isBlank() ? summarizer.trim() : null,
                    resolvedProfileId
            );
            
            Map<String, String> response = new HashMap<>();
            response.put("status", "success");
            response.put("message", "Test task added to queue");
            response.put("taskId", taskId);
            response.put("fileName", fileName);
            
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
            
        } catch (IllegalArgumentException e) {
            log.error("Invalid tool specified: {}", tool, e);
            return ResponseHelper.buildErrorResponse(HttpStatus.BAD_REQUEST, "Invalid tool: " + e.getMessage());
        } catch (IOException e) {
            log.error("Error uploading file", e);
            return ResponseHelper.buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to upload file: " + e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error uploading file", e);
            return ResponseHelper.buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to upload file: " + e.getMessage());
        }
    }
}
