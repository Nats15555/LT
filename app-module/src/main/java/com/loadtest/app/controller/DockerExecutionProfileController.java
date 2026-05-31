package com.loadtest.app.controller;

import com.loadtest.app.dto.CreateDockerProfileRequest;
import com.loadtest.app.dto.DockerProfileDto;
import com.loadtest.app.dto.UpdateDockerProfileRequest;
import com.loadtest.app.service.DockerExecutionProfileService;
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
@RequestMapping("/api/v1/loadtest/docker-profiles")
@RequiredArgsConstructor
public class DockerExecutionProfileController {

    private final DockerExecutionProfileService profileService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(@RequestParam(value = "enabledOnly", defaultValue = "false") boolean enabledOnly) {
        try {
            List<DockerProfileDto> list = enabledOnly ? profileService.listEnabledProfiles() : profileService.listProfiles();
            return ResponseHelper.buildSuccessResponse(HttpStatus.OK, ApiMessages.DockerProfile.OK, Map.of(ApiJsonKeys.PROFILES, list));
        } catch (RuntimeException e) {
            log.error("list docker profiles", e);
            return ResponseHelper.buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getOne(@PathVariable UUID id) {
        try {
            DockerProfileDto dto = profileService.getById(id);
            return ResponseHelper.buildSuccessResponse(HttpStatus.OK, ApiMessages.DockerProfile.OK, Map.of(ApiJsonKeys.PROFILE, dto));
        } catch (IllegalArgumentException e) {
            return ResponseHelper.buildErrorResponse(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (RuntimeException e) {
            log.error("get docker profile", e);
            return ResponseHelper.buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody CreateDockerProfileRequest request) {
        try {
            DockerProfileDto dto = profileService.create(request);
            return ResponseHelper.buildSuccessResponse(HttpStatus.CREATED, ApiMessages.DockerProfile.CREATED, Map.of(ApiJsonKeys.PROFILE, dto));
        } catch (IllegalStateException e) {
            return ResponseHelper.buildErrorResponse(HttpStatus.CONFLICT, e.getMessage());
        } catch (IllegalArgumentException e) {
            return ResponseHelper.buildErrorResponse(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (RuntimeException e) {
            log.error("create docker profile", e);
            return ResponseHelper.buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable UUID id, @RequestBody UpdateDockerProfileRequest request) {
        try {
            DockerProfileDto dto = profileService.update(id, request);
            return ResponseHelper.buildSuccessResponse(HttpStatus.OK, ApiMessages.DockerProfile.UPDATED, Map.of(ApiJsonKeys.PROFILE, dto));
        } catch (IllegalStateException e) {
            return ResponseHelper.buildErrorResponse(HttpStatus.CONFLICT, e.getMessage());
        } catch (IllegalArgumentException e) {
            return ResponseHelper.buildErrorResponse(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (RuntimeException e) {
            log.error("update docker profile", e);
            return ResponseHelper.buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable UUID id) {
        try {
            profileService.delete(id);
            return ResponseEntity.ok(ResponseHelper.simpleSuccessBody(ApiMessages.DockerProfile.DELETED));
        } catch (IllegalStateException e) {
            return ResponseHelper.buildErrorResponse(HttpStatus.CONFLICT, e.getMessage());
        } catch (IllegalArgumentException e) {
            return ResponseHelper.buildErrorResponse(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (RuntimeException e) {
            log.error("delete docker profile", e);
            return ResponseHelper.buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }
}
