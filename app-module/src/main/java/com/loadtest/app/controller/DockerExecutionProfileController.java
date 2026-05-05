package com.loadtest.app.controller;

import com.loadtest.app.dto.CreateDockerProfileRequest;
import com.loadtest.app.dto.DockerProfileDto;
import com.loadtest.app.dto.UpdateDockerProfileRequest;
import com.loadtest.app.service.DockerExecutionProfileService;
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
@RequestMapping("/api/v1/loadtest/docker-profiles")
@RequiredArgsConstructor
public class DockerExecutionProfileController {

    private final DockerExecutionProfileService profileService;

    @GetMapping
    public ResponseEntity<?> list(@RequestParam(value = "enabledOnly", defaultValue = "false") boolean enabledOnly) {
        try {
            List<DockerProfileDto> list = enabledOnly ? profileService.listEnabledProfiles() : profileService.listProfiles();
            Map<String, Object> data = new HashMap<>();
            data.put("profiles", list);
            return ResponseHelper.buildSuccessResponse(HttpStatus.OK, "OK", data);
        } catch (Exception e) {
            log.error("list docker profiles", e);
            return ResponseHelper.buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getOne(@PathVariable UUID id) {
        try {
            DockerProfileDto dto = profileService.getById(id);
            Map<String, Object> data = new HashMap<>();
            data.put("profile", dto);
            return ResponseHelper.buildSuccessResponse(HttpStatus.OK, "OK", data);
        } catch (IllegalArgumentException e) {
            return ResponseHelper.buildErrorResponse(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (Exception e) {
            log.error("get docker profile", e);
            return ResponseHelper.buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody CreateDockerProfileRequest request) {
        try {
            DockerProfileDto dto = profileService.create(request);
            Map<String, Object> data = new HashMap<>();
            data.put("profile", dto);
            return ResponseHelper.buildSuccessResponse(HttpStatus.CREATED, "Profile created", data);
        } catch (IllegalStateException e) {
            return ResponseHelper.buildErrorResponse(HttpStatus.CONFLICT, e.getMessage());
        } catch (IllegalArgumentException e) {
            return ResponseHelper.buildErrorResponse(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            log.error("create docker profile", e);
            return ResponseHelper.buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable UUID id, @RequestBody UpdateDockerProfileRequest request) {
        try {
            DockerProfileDto dto = profileService.update(id, request);
            Map<String, Object> data = new HashMap<>();
            data.put("profile", dto);
            return ResponseHelper.buildSuccessResponse(HttpStatus.OK, "Profile updated", data);
        } catch (IllegalStateException e) {
            return ResponseHelper.buildErrorResponse(HttpStatus.CONFLICT, e.getMessage());
        } catch (IllegalArgumentException e) {
            return ResponseHelper.buildErrorResponse(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (Exception e) {
            log.error("update docker profile", e);
            return ResponseHelper.buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable UUID id) {
        try {
            profileService.delete(id);
            return ResponseEntity.ok(Map.of("status", "success", "message", "Profile deleted"));
        } catch (IllegalStateException e) {
            return ResponseHelper.buildErrorResponse(HttpStatus.CONFLICT, e.getMessage());
        } catch (IllegalArgumentException e) {
            return ResponseHelper.buildErrorResponse(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (Exception e) {
            log.error("delete docker profile", e);
            return ResponseHelper.buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }
}
