package com.loadtest.app.controller;

import com.loadtest.app.service.LoadTestUploadService;
import com.loadtest.app.util.ApiJsonKeys;
import com.loadtest.app.util.ApiMessages;
import com.loadtest.app.util.ClasspathResources;
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
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/loadtest")
public class LoadTestController {

    private final LoadTestUploadService loadTestUploadService;

    public LoadTestController(LoadTestUploadService loadTestUploadService) {
        this.loadTestUploadService = loadTestUploadService;
    }

    @GetMapping(value = "/metrics-config-schema", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Resource> getMetricsConfigSchema() {
        return ResponseEntity.ok(new ClassPathResource(ClasspathResources.METRICS_CONFIG_SCHEMA));
    }

    @GetMapping(value = "/standard-summarization-prompt-template", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getStandardSummarizationPromptTemplate() {
        return ResponseEntity.ok(Map.of(
                ApiJsonKeys.TEMPLATE,
                ClasspathResources.readUtf8(ClasspathResources.STANDARD_SUMMARIZATION_PROMPT_TEMPLATE),
                ApiJsonKeys.DESCRIPTION, ApiMessages.PromptTemplate.DESCRIPTION
        ));
    }

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> uploadTestFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam("tool") String tool,
            @RequestParam("command") String command,
            @RequestParam("expectedDurationSeconds") Integer expectedDurationSeconds,
            @RequestParam(value = "metricsConfig", required = false) String metricsConfig,
            @RequestParam(value = "summarizer", required = false) String summarizer,
            @RequestParam(value = "customPrompt", required = false) String customPrompt,
            @RequestParam("dockerExecutionProfileId") String dockerExecutionProfileId) {
        log.info("Uploading test file: {}, tool: {}", file.getOriginalFilename(), tool);
        try {
            return loadTestUploadService.upload(
                    file, tool, command, expectedDurationSeconds,
                    metricsConfig, summarizer, customPrompt, dockerExecutionProfileId);
        } catch (LoadTestUploadService.ScenarioFileTooLargeException e) {
            log.warn("Scenario file too large for tool {}: {}", tool, e.getMessage());
            return ResponseHelper.buildErrorResponse(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalArgumentException e) {
            log.error("Invalid upload request for tool {}: {}", tool, e.getMessage());
            return ResponseHelper.buildErrorResponse(HttpStatus.BAD_REQUEST,
                    ApiMessages.Upload.INVALID_TOOL_PREFIX + e.getMessage());
        } catch (IOException e) {
            log.error("Error uploading file", e);
            return ResponseHelper.buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                    ApiMessages.Upload.FAILED_UPLOAD_PREFIX + e.getMessage());
        } catch (RuntimeException e) {
            log.error("Unexpected error uploading file", e);
            return ResponseHelper.buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                    ApiMessages.Upload.FAILED_UPLOAD_PREFIX + e.getMessage());
        }
    }
}
