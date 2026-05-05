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

@WebMvcTest(controllers = DockerExecutionProfileController.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DockerExecutionProfileControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private DockerExecutionProfileService profileService;

    @Test
    void list_allAndEnabledOnly() throws Exception {
        DockerProfileDto dto = DockerProfileDto.builder()
                .id(UUID.randomUUID())
                .name("p")
                .enabled(true)
                .createdAt(OffsetDateTime.MIN)
                .updatedAt(OffsetDateTime.MIN)
                .build();
        when(profileService.listProfiles()).thenReturn(List.of(dto));
        when(profileService.listEnabledProfiles()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/loadtest/docker-profiles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.profiles[0].name").value("p"));

        mockMvc.perform(get("/api/v1/loadtest/docker-profiles").param("enabledOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profiles").isEmpty());
    }

    @Test
    void getOne_okAndNotFound() throws Exception {
        UUID id = UUID.randomUUID();
        when(profileService.getById(id)).thenReturn(DockerProfileDto.builder()
                .id(id)
                .name("n")
                .enabled(true)
                .createdAt(OffsetDateTime.MIN)
                .updatedAt(OffsetDateTime.MIN)
                .build());

        mockMvc.perform(get("/api/v1/loadtest/docker-profiles/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile.name").value("n"));

        when(profileService.getById(id)).thenThrow(new IllegalArgumentException("missing"));
        mockMvc.perform(get("/api/v1/loadtest/docker-profiles/{id}", id))
                .andExpect(status().isNotFound());
    }

    @Test
    void getOne_runtimeError_returns500() throws Exception {
        UUID id = UUID.randomUUID();
        when(profileService.getById(id)).thenThrow(new RuntimeException("boom"));
        mockMvc.perform(get("/api/v1/loadtest/docker-profiles/{id}", id))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void create_conflictAndCreated() throws Exception {
        CreateDockerProfileRequest req = CreateDockerProfileRequest.builder().name("new").enabled(true).build();
        doThrow(new IllegalStateException("busy")).when(profileService).create(any());
        mockMvc.perform(post("/api/v1/loadtest/docker-profiles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict());

        reset(profileService);
        when(profileService.create(any())).thenReturn(DockerProfileDto.builder()
                .id(UUID.randomUUID())
                .name("new")
                .enabled(true)
                .createdAt(OffsetDateTime.MIN)
                .updatedAt(OffsetDateTime.MIN)
                .build());
        mockMvc.perform(post("/api/v1/loadtest/docker-profiles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.profile.name").value("new"));
    }

    @Test
    void update_andDelete() throws Exception {
        UUID id = UUID.randomUUID();
        UpdateDockerProfileRequest body = new UpdateDockerProfileRequest();
        body.setMemoryLimitMb(128);
        when(profileService.update(eq(id), any())).thenReturn(DockerProfileDto.builder()
                .id(id)
                .name("x")
                .memoryLimitMb(128)
                .enabled(true)
                .createdAt(OffsetDateTime.MIN)
                .updatedAt(OffsetDateTime.MIN)
                .build());

        mockMvc.perform(put("/api/v1/loadtest/docker-profiles/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile.memoryLimitMb").value(128));

        mockMvc.perform(delete("/api/v1/loadtest/docker-profiles/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"));
    }

    @Test
    void list_runtimeError_returns500() throws Exception {
        when(profileService.listProfiles()).thenThrow(new RuntimeException("db"));
        mockMvc.perform(get("/api/v1/loadtest/docker-profiles"))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void create_badRequest_and_runtime500() throws Exception {
        CreateDockerProfileRequest req = CreateDockerProfileRequest.builder().name("x").enabled(true).build();
        doThrow(new IllegalArgumentException("bad")).when(profileService).create(any());
        mockMvc.perform(post("/api/v1/loadtest/docker-profiles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());

        reset(profileService);
        doThrow(new RuntimeException("boom")).when(profileService).create(any());
        mockMvc.perform(post("/api/v1/loadtest/docker-profiles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void update_errorBranches() throws Exception {
        UUID id = UUID.randomUUID();
        UpdateDockerProfileRequest body = new UpdateDockerProfileRequest();
        doThrow(new IllegalStateException("conflict"))
                .doThrow(new IllegalArgumentException("missing"))
                .doThrow(new RuntimeException("boom"))
                .when(profileService).update(eq(id), any());
        mockMvc.perform(put("/api/v1/loadtest/docker-profiles/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isConflict());

        mockMvc.perform(put("/api/v1/loadtest/docker-profiles/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isNotFound());

        mockMvc.perform(put("/api/v1/loadtest/docker-profiles/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void delete_errorBranches() throws Exception {
        UUID id = UUID.randomUUID();
        doThrow(new IllegalStateException("conflict")).when(profileService).delete(id);
        mockMvc.perform(delete("/api/v1/loadtest/docker-profiles/{id}", id))
                .andExpect(status().isConflict());

        doThrow(new IllegalArgumentException("missing")).when(profileService).delete(id);
        mockMvc.perform(delete("/api/v1/loadtest/docker-profiles/{id}", id))
                .andExpect(status().isNotFound());

        doThrow(new RuntimeException("boom")).when(profileService).delete(id);
        mockMvc.perform(delete("/api/v1/loadtest/docker-profiles/{id}", id))
                .andExpect(status().isInternalServerError());
    }
}
