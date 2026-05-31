package com.loadtest.app.service;

import com.loadtest.app.dto.LoadTestToolDto;
import com.loadtest.app.dto.SummarizerModelDto;
import com.loadtest.app.dto.TestTaskMessage;
import com.loadtest.app.util.ApiJsonKeys;
import com.loadtest.app.util.ApiMessages;
import com.loadtest.app.util.ApiResponseValues;
import com.loadtest.app.util.FileValidationHelper;
import com.loadtest.app.util.MetricsConfigParser;
import com.loadtest.app.util.ResponseHelper;
import com.loadtest.app.util.SummarizerProviders;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class LoadTestUploadService {

    private final TestQueueService testQueueService;
    private final MetricsConfigParser metricsConfigParser;
    private final LoadTestToolService loadTestToolService;
    private final SummarizerService summarizerService;
    private final DockerExecutionProfileService dockerExecutionProfileService;
    private final CustomSummarizationPromptStore customSummarizationPromptStore;

    public ResponseEntity<Map<String, Object>> upload(
            MultipartFile file,
            String tool,
            String command,
            Integer expectedDurationSeconds,
            String metricsConfig,
            String summarizer,
            String customPrompt,
            String dockerExecutionProfileId) throws IOException {

        ResponseEntity<Map<String, Object>> requiredFieldsError = validateRequiredFields(command, expectedDurationSeconds);
        if (requiredFieldsError != null) {
            return requiredFieldsError;
        }

        ResolvedTool resolvedTool = resolveTool(tool);
        if (resolvedTool.error() != null) {
            return resolvedTool.error();
        }

        ResponseEntity<Map<String, Object>> summarizerError = validateSummarizerIfPresent(summarizer);
        if (summarizerError != null) {
            return summarizerError;
        }

        ResponseEntity<Map<String, Object>> fileError = validateUploadedFile(file, resolvedTool.tool());
        if (fileError != null) {
            return fileError;
        }

        String fileName = file.getOriginalFilename();
        String fileContentBase64 = encodeFileContent(file);

        MetricsConfigParseResult metricsResult = parseOptionalMetricsConfig(metricsConfig);
        if (metricsResult.error() != null) {
            return metricsResult.error();
        }

        UUID resolvedProfileId = dockerExecutionProfileService.resolveProfileIdForUpload(dockerExecutionProfileId);
        String summarizerName = normalizeSummarizerName(summarizer);
        String taskId = testQueueService.enqueueTest(
                tool.toUpperCase(),
                fileName,
                fileContentBase64,
                command,
                expectedDurationSeconds,
                metricsResult.config(),
                metricsConfig,
                summarizerName,
                resolvedProfileId);

        storeCustomPromptIfPresent(taskId, customPrompt);
        return acceptedResponse(taskId, fileName);
    }

    private static ResponseEntity<Map<String, Object>> validateRequiredFields(String command, Integer expectedDurationSeconds) {
        if (command == null || command.isBlank()) {
            return ResponseHelper.buildErrorResponse(HttpStatus.BAD_REQUEST, ApiMessages.Upload.COMMAND_REQUIRED);
        }
        if (expectedDurationSeconds == null || expectedDurationSeconds < 1) {
            return ResponseHelper.buildErrorResponse(HttpStatus.BAD_REQUEST,
                    ApiMessages.Upload.EXPECTED_DURATION_REQUIRED);
        }
        return null;
    }

    private ResolvedTool resolveTool(String tool) {
        LoadTestToolDto toolDto;
        try {
            toolDto = loadTestToolService.getToolByName(tool.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResolvedTool.error(ResponseHelper.buildErrorResponse(HttpStatus.BAD_REQUEST,
                    ApiMessages.Upload.toolNotFound(tool)));
        }
        if (Boolean.FALSE.equals(toolDto.enabled())) {
            return ResolvedTool.error(ResponseHelper.buildErrorResponse(HttpStatus.BAD_REQUEST,
                    ApiMessages.Upload.toolDisabled(tool)));
        }
        return ResolvedTool.ok(toolDto);
    }

    private ResponseEntity<Map<String, Object>> validateSummarizerIfPresent(String summarizer) {
        if (summarizer == null || summarizer.isBlank()) {
            return null;
        }
        try {
            SummarizerModelDto dto = summarizerService.getByName(summarizer.trim());
            return validateSummarizerRoute(summarizer, dto);
        } catch (IllegalArgumentException e) {
            return ResponseHelper.buildErrorResponse(HttpStatus.BAD_REQUEST,
                    ApiMessages.Upload.summarizerNotFound(summarizer));
        }
    }

    private static ResponseEntity<Map<String, Object>> validateSummarizerRoute(String summarizer, SummarizerModelDto dto) {
        if (dto.enabled() == null || !dto.enabled()) {
            return ResponseHelper.buildErrorResponse(HttpStatus.BAD_REQUEST,
                    ApiMessages.Upload.summarizerDisabled(summarizer));
        }
        if (!SummarizerProviders.EXTERNAL.equalsIgnoreCase(dto.provider())) {
            return null;
        }
        String baseUrl = dto.baseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            return ResponseHelper.buildErrorResponse(HttpStatus.BAD_REQUEST,
                    ApiMessages.Upload.EXTERNAL_BASE_URL_REQUIRED);
        }
        if (!baseUrl.trim().matches("(?i)https?://.*")) {
            return ResponseHelper.buildErrorResponse(HttpStatus.BAD_REQUEST,
                    ApiMessages.Upload.EXTERNAL_BASE_URL_SCHEME);
        }
        return null;
    }

    private static ResponseEntity<Map<String, Object>> validateUploadedFile(MultipartFile file, LoadTestToolDto toolDto) {
        String fileValidationError = FileValidationHelper.validateFile(file);
        if (fileValidationError != null) {
            return ResponseHelper.buildErrorResponse(HttpStatus.BAD_REQUEST, fileValidationError);
        }

        String fileName = file.getOriginalFilename();
        String fileExtension = FileValidationHelper.getFileExtension(fileName);
        List<String> allowedExtensions = toolDto.fileExtensions() != null ? toolDto.fileExtensions() : List.of();
        String allowedExtensionsLabel = String.join(", ", allowedExtensions);

        if (fileExtension.isEmpty()) {
            return ResponseHelper.buildErrorResponse(HttpStatus.BAD_REQUEST,
                    ApiMessages.Upload.fileExtensionRequired(toolDto.name(), allowedExtensionsLabel));
        }

        boolean extensionMatches = allowedExtensions.stream()
                .anyMatch(ext -> ext.replace(".", "").equalsIgnoreCase(fileExtension));
        if (!extensionMatches) {
            return ResponseHelper.buildErrorResponse(HttpStatus.BAD_REQUEST,
                    ApiMessages.Upload.fileExtensionMismatch(
                            toolDto.name(), allowedExtensionsLabel, fileExtension));
        }

        log.info("File extension validated: .{} for tool {}", fileExtension, toolDto.name());
        return null;
    }

    private static String encodeFileContent(MultipartFile file) throws IOException {
        byte[] fileBytes = file.getBytes();
        String fileContentBase64 = Base64.getEncoder().encodeToString(fileBytes);
        log.info("File prepared for transmission: {} ({} bytes, Base64: {} chars)",
                file.getOriginalFilename(), fileBytes.length, fileContentBase64.length());
        return fileContentBase64;
    }

    private MetricsConfigParseResult parseOptionalMetricsConfig(String metricsConfig) {
        if (metricsConfig == null || metricsConfig.trim().isEmpty()) {
            return MetricsConfigParseResult.ok(null);
        }
        try {
            TestTaskMessage.MetricsConfig config = metricsConfigParser.parseMetricsConfigRequests(metricsConfig);
            log.info("Metrics collection configured for task: {} requests, delay: {}s",
                    config.requests() != null ? config.requests().size() : 0,
                    config.delaySeconds());
            return MetricsConfigParseResult.ok(config);
        } catch (RuntimeException e) {
            log.error("Failed to parse metrics configuration", e);
            return MetricsConfigParseResult.error(ResponseHelper.buildErrorResponse(HttpStatus.BAD_REQUEST,
                    ApiMessages.Upload.INVALID_METRICS_CONFIG_PREFIX + e.getMessage()));
        }
    }

    private static String normalizeSummarizerName(String summarizer) {
        if (summarizer == null || summarizer.isBlank()) {
            return null;
        }
        return summarizer.trim();
    }

    private void storeCustomPromptIfPresent(String taskId, String customPrompt) {
        if (customPrompt == null || customPrompt.isBlank()) {
            return;
        }
        customSummarizationPromptStore.put(UUID.fromString(taskId), customPrompt);
        log.info("Stored custom prompt from /upload for taskId={} (in-memory, not DB)", taskId);
    }

    private static ResponseEntity<Map<String, Object>> acceptedResponse(String taskId, String fileName) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                ApiJsonKeys.STATUS, ApiResponseValues.STATUS_SUCCESS,
                ApiJsonKeys.MESSAGE, ApiMessages.Upload.TASK_ADDED,
                ApiJsonKeys.TASK_ID, taskId,
                ApiJsonKeys.FILE_NAME, fileName));
    }

    private record ResolvedTool(LoadTestToolDto tool, ResponseEntity<Map<String, Object>> error) {
        static ResolvedTool ok(LoadTestToolDto tool) {
            return new ResolvedTool(tool, null);
        }

        static ResolvedTool error(ResponseEntity<Map<String, Object>> error) {
            return new ResolvedTool(null, error);
        }
    }

    private record MetricsConfigParseResult(
            TestTaskMessage.MetricsConfig config,
            ResponseEntity<Map<String, Object>> error) {
        static MetricsConfigParseResult ok(TestTaskMessage.MetricsConfig config) {
            return new MetricsConfigParseResult(config, null);
        }

        static MetricsConfigParseResult error(ResponseEntity<Map<String, Object>> error) {
            return new MetricsConfigParseResult(null, error);
        }
    }
}
