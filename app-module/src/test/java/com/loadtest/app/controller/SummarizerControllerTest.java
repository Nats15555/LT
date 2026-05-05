package com.loadtest.app.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadtest.app.dto.CreateSummarizerRequest;
import com.loadtest.app.dto.SummarizerModelDto;
import com.loadtest.app.dto.UpdateSummarizerRequest;
import com.loadtest.app.service.SummarizerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = SummarizerController.class)
class SummarizerControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private SummarizerService summarizerService;

    private SummarizerModelDto sample() {
        return SummarizerModelDto.builder()
                .id(UUID.randomUUID())
                .name("s1")
                .provider("OPENAI")
                .modelId("m")
                .enabled(true)
                .createdAt(OffsetDateTime.MIN)
                .updatedAt(OffsetDateTime.MIN)
                .build();
    }

    @Test
    void create_getList_getById_getByName_update_delete() throws Exception {
        CreateSummarizerRequest createReq = CreateSummarizerRequest.builder()
                .name("s1")
                .modelId("m")
                .enabled(true)
                .build();
        when(summarizerService.create(any())).thenReturn(sample());

        mockMvc.perform(post("/api/v1/loadtest/summarizers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("s1"));

        when(summarizerService.getAll()).thenReturn(List.of(sample()));
        mockMvc.perform(get("/api/v1/loadtest/summarizers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("s1"));

        when(summarizerService.getEnabled()).thenReturn(List.of());
        mockMvc.perform(get("/api/v1/loadtest/summarizers").param("enabled", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());

        UUID id = UUID.randomUUID();
        when(summarizerService.getById(id)).thenReturn(sample());
        mockMvc.perform(get("/api/v1/loadtest/summarizers/{id}", id))
                .andExpect(status().isOk());

        when(summarizerService.getByName("s1")).thenReturn(sample());
        mockMvc.perform(get("/api/v1/loadtest/summarizers/name/{name}", "s1"))
                .andExpect(status().isOk());

        UpdateSummarizerRequest upd = new UpdateSummarizerRequest();
        upd.setEnabled(false);
        when(summarizerService.update(eq(id), any())).thenReturn(sample());
        mockMvc.perform(put("/api/v1/loadtest/summarizers/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(upd)))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/loadtest/summarizers/{id}", id))
                .andExpect(status().isOk());
    }

    @Test
    void getById_notFound() throws Exception {
        UUID id = UUID.randomUUID();
        when(summarizerService.getById(id)).thenThrow(new IllegalArgumentException("nope"));
        mockMvc.perform(get("/api/v1/loadtest/summarizers/{id}", id))
                .andExpect(status().isNotFound());
    }

    @Test
    void runtimeExceptions_return500() throws Exception {
        when(summarizerService.getAll()).thenThrow(new RuntimeException("db"));
        mockMvc.perform(get("/api/v1/loadtest/summarizers"))
                .andExpect(status().isInternalServerError());

        when(summarizerService.getByName("x")).thenThrow(new RuntimeException("boom"));
        mockMvc.perform(get("/api/v1/loadtest/summarizers/name/{name}", "x"))
                .andExpect(status().isInternalServerError());

        UUID id = UUID.randomUUID();
        when(summarizerService.getById(id)).thenThrow(new RuntimeException("boom"));
        mockMvc.perform(get("/api/v1/loadtest/summarizers/{id}", id))
                .andExpect(status().isInternalServerError());

        when(summarizerService.update(eq(id), any())).thenThrow(new RuntimeException("boom"));
        mockMvc.perform(put("/api/v1/loadtest/summarizers/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateSummarizerRequest())))
                .andExpect(status().isInternalServerError());

        doThrow(new RuntimeException("boom")).when(summarizerService).delete(id);
        mockMvc.perform(delete("/api/v1/loadtest/summarizers/{id}", id))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void create_and_notFound_branches() throws Exception {
        CreateSummarizerRequest req = CreateSummarizerRequest.builder().name("x").modelId("m").build();
        when(summarizerService.create(any())).thenThrow(new IllegalArgumentException("bad"));
        mockMvc.perform(post("/api/v1/loadtest/summarizers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());

        UUID id = UUID.randomUUID();
        when(summarizerService.getByName("missing")).thenThrow(new IllegalArgumentException("no"));
        mockMvc.perform(get("/api/v1/loadtest/summarizers/name/{name}", "missing"))
                .andExpect(status().isNotFound());

        when(summarizerService.update(eq(id), any())).thenThrow(new IllegalArgumentException("no"));
        mockMvc.perform(put("/api/v1/loadtest/summarizers/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateSummarizerRequest())))
                .andExpect(status().isNotFound());

        doThrow(new IllegalArgumentException("no")).when(summarizerService).delete(id);
        mockMvc.perform(delete("/api/v1/loadtest/summarizers/{id}", id))
                .andExpect(status().isNotFound());
    }

    @Test
    void getAll_withEnabledFalse_usesAllBranch() throws Exception {
        when(summarizerService.getAll()).thenReturn(List.of(sample()));
        mockMvc.perform(get("/api/v1/loadtest/summarizers").param("enabled", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("s1"));
    }
}
