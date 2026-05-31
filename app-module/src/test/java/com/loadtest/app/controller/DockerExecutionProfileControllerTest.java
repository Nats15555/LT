package com.loadtest.app.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadtest.app.dto.CreateDockerProfileRequest;
import com.loadtest.app.dto.DockerProfileDto;
import com.loadtest.app.dto.UpdateDockerProfileRequest;
import com.loadtest.app.service.DockerExecutionProfileService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static com.loadtest.app.testsupport.JsonTestSupport.writeValueAsString;
import static com.loadtest.app.testsupport.MockMvcTestSupport.perform;

@WebMvcTest(controllers = DockerExecutionProfileController.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DockerExecutionProfileControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private DockerExecutionProfileService profileService;

    private static DockerProfileDto profileDto(UUID id, String name, Integer memoryLimitMb) {
        return new DockerProfileDto(
                id, name, null, null, memoryLimitMb,
                null, null, null, null, null, null, null, null, null, null, null, null,
                true, OffsetDateTime.MIN, OffsetDateTime.MIN);
    }

    private static UpdateDockerProfileRequest emptyUpdate() {
        return new UpdateDockerProfileRequest(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void list_allAndEnabledOnly() {
        DockerProfileDto dto = profileDto(UUID.randomUUID(), "p", null);
        when(profileService.listProfiles()).thenReturn(List.of(dto));
        when(profileService.listEnabledProfiles()).thenReturn(List.of());

        perform(mockMvc, get("/api/v1/loadtest/docker-profiles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.profiles[0].name").value("p"));

        perform(mockMvc, get("/api/v1/loadtest/docker-profiles").param("enabledOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profiles").isEmpty());
    }

    @Test
    void getOne_okAndNotFound() {
        UUID id = UUID.randomUUID();
        when(profileService.getById(id)).thenReturn(profileDto(id, "n", null));

        perform(mockMvc, get("/api/v1/loadtest/docker-profiles/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile.name").value("n"));

        when(profileService.getById(id)).thenThrow(new IllegalArgumentException("missing"));
        perform(mockMvc, get("/api/v1/loadtest/docker-profiles/{id}", id))
                .andExpect(status().isNotFound());
    }

    @Test
    void getOne_runtimeError_returns500() {
        UUID id = UUID.randomUUID();
        when(profileService.getById(id)).thenThrow(new RuntimeException("boom"));
        perform(mockMvc, get("/api/v1/loadtest/docker-profiles/{id}", id))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void create_conflictAndCreated() {
        CreateDockerProfileRequest req = new CreateDockerProfileRequest(
                "new", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, true);
        doThrow(new IllegalStateException("busy")).when(profileService).create(any());
        perform(mockMvc, post("/api/v1/loadtest/docker-profiles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(writeValueAsString(objectMapper,req)))
                .andExpect(status().isConflict());

        reset(profileService);
        when(profileService.create(any())).thenReturn(profileDto(UUID.randomUUID(), "new", null));
        perform(mockMvc, post("/api/v1/loadtest/docker-profiles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(writeValueAsString(objectMapper,req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.profile.name").value("new"));
    }

    @Test
    void update_andDelete() {
        UUID id = UUID.randomUUID();
        UpdateDockerProfileRequest body = new UpdateDockerProfileRequest(
                null, null, null, 128, null, null, null, null, null, null, null, null, null, null, null, null, null);
        when(profileService.update(eq(id), any())).thenReturn(profileDto(id, "x", 128));

        perform(mockMvc, put("/api/v1/loadtest/docker-profiles/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(writeValueAsString(objectMapper,body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile.memoryLimitMb").value(128));

        perform(mockMvc, delete("/api/v1/loadtest/docker-profiles/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"));
    }

    @Test
    void list_runtimeError_returns500() {
        when(profileService.listProfiles()).thenThrow(new RuntimeException("db"));
        perform(mockMvc, get("/api/v1/loadtest/docker-profiles"))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void create_badRequest_and_runtime500() {
        CreateDockerProfileRequest req = new CreateDockerProfileRequest(
                "x", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, true);
        doThrow(new IllegalArgumentException("bad")).when(profileService).create(any());
        perform(mockMvc, post("/api/v1/loadtest/docker-profiles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(writeValueAsString(objectMapper,req)))
                .andExpect(status().isBadRequest());

        reset(profileService);
        doThrow(new RuntimeException("boom")).when(profileService).create(any());
        perform(mockMvc, post("/api/v1/loadtest/docker-profiles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(writeValueAsString(objectMapper,req)))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void update_errorBranches() {
        UUID id = UUID.randomUUID();
        UpdateDockerProfileRequest body = emptyUpdate();
        doThrow(new IllegalStateException("conflict"))
                .doThrow(new IllegalArgumentException("missing"))
                .doThrow(new RuntimeException("boom"))
                .when(profileService).update(eq(id), any());
        perform(mockMvc, put("/api/v1/loadtest/docker-profiles/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(writeValueAsString(objectMapper,body)))
                .andExpect(status().isConflict());

        perform(mockMvc, put("/api/v1/loadtest/docker-profiles/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(writeValueAsString(objectMapper,body)))
                .andExpect(status().isNotFound());

        perform(mockMvc, put("/api/v1/loadtest/docker-profiles/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(writeValueAsString(objectMapper,body)))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void delete_errorBranches() {
        UUID id = UUID.randomUUID();
        doThrow(new IllegalStateException("conflict")).when(profileService).delete(id);
        perform(mockMvc, delete("/api/v1/loadtest/docker-profiles/{id}", id))
                .andExpect(status().isConflict());

        doThrow(new IllegalArgumentException("missing")).when(profileService).delete(id);
        perform(mockMvc, delete("/api/v1/loadtest/docker-profiles/{id}", id))
                .andExpect(status().isNotFound());

        doThrow(new RuntimeException("boom")).when(profileService).delete(id);
        perform(mockMvc, delete("/api/v1/loadtest/docker-profiles/{id}", id))
                .andExpect(status().isInternalServerError());
    }
}
