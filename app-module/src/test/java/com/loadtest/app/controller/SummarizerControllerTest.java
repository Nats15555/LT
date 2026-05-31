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
import static com.loadtest.app.testsupport.JsonTestSupport.writeValueAsString;
import static com.loadtest.app.testsupport.MockMvcTestSupport.perform;

@WebMvcTest(controllers = SummarizerController.class)
class SummarizerControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private SummarizerService summarizerService;

    private SummarizerModelDto sample() {
        return new SummarizerModelDto(
                UUID.randomUUID(), "s1", "OPENAI", null, "m", null, true, OffsetDateTime.MIN, OffsetDateTime.MIN);
    }

    @Test
    void create_getList_getById_getByName_update_delete() {
        CreateSummarizerRequest createReq = new CreateSummarizerRequest("s1", null, null, "m", null, true);
        when(summarizerService.create(any())).thenReturn(sample());

        perform(mockMvc, post("/api/v1/loadtest/summarizers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(writeValueAsString(objectMapper, createReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("s1"));

        when(summarizerService.getAll()).thenReturn(List.of(sample()));
        perform(mockMvc, get("/api/v1/loadtest/summarizers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("s1"));

        when(summarizerService.getEnabled()).thenReturn(List.of());
        perform(mockMvc, get("/api/v1/loadtest/summarizers").param("enabled", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());

        UUID id = UUID.randomUUID();
        when(summarizerService.getById(id)).thenReturn(sample());
        perform(mockMvc, get("/api/v1/loadtest/summarizers/{id}", id))
                .andExpect(status().isOk());

        when(summarizerService.getByName("s1")).thenReturn(sample());
        perform(mockMvc, get("/api/v1/loadtest/summarizers/name/{name}", "s1"))
                .andExpect(status().isOk());

        UpdateSummarizerRequest upd = new UpdateSummarizerRequest(null, null, null, null, false);
        when(summarizerService.update(eq(id), any())).thenReturn(sample());
        perform(mockMvc, put("/api/v1/loadtest/summarizers/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(writeValueAsString(objectMapper, upd)))
                .andExpect(status().isOk());

        perform(mockMvc, delete("/api/v1/loadtest/summarizers/{id}", id))
                .andExpect(status().isOk());
    }

    @Test
    void getById_notFound() {
        UUID id = UUID.randomUUID();
        when(summarizerService.getById(id)).thenThrow(new IllegalArgumentException("nope"));
        perform(mockMvc, get("/api/v1/loadtest/summarizers/{id}", id))
                .andExpect(status().isNotFound());
    }

    @Test
    void runtimeExceptions_return500() {
        when(summarizerService.getAll()).thenThrow(new RuntimeException("db"));
        perform(mockMvc, get("/api/v1/loadtest/summarizers"))
                .andExpect(status().isInternalServerError());

        when(summarizerService.getByName("x")).thenThrow(new RuntimeException("boom"));
        perform(mockMvc, get("/api/v1/loadtest/summarizers/name/{name}", "x"))
                .andExpect(status().isInternalServerError());

        UUID id = UUID.randomUUID();
        when(summarizerService.getById(id)).thenThrow(new RuntimeException("boom"));
        perform(mockMvc, get("/api/v1/loadtest/summarizers/{id}", id))
                .andExpect(status().isInternalServerError());

        when(summarizerService.update(eq(id), any())).thenThrow(new RuntimeException("boom"));
        perform(mockMvc, put("/api/v1/loadtest/summarizers/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(writeValueAsString(objectMapper, new UpdateSummarizerRequest(null, null, null, null, null))))
                .andExpect(status().isInternalServerError());

        doThrow(new RuntimeException("boom")).when(summarizerService).delete(id);
        perform(mockMvc, delete("/api/v1/loadtest/summarizers/{id}", id))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void create_and_notFound_branches() {
        CreateSummarizerRequest req = new CreateSummarizerRequest("x", null, null, "m", null, null);
        when(summarizerService.create(any())).thenThrow(new IllegalArgumentException("bad"));
        perform(mockMvc, post("/api/v1/loadtest/summarizers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(writeValueAsString(objectMapper, req)))
                .andExpect(status().isBadRequest());

        UUID id = UUID.randomUUID();
        when(summarizerService.getByName("missing")).thenThrow(new IllegalArgumentException("no"));
        perform(mockMvc, get("/api/v1/loadtest/summarizers/name/{name}", "missing"))
                .andExpect(status().isNotFound());

        when(summarizerService.update(eq(id), any())).thenThrow(new IllegalArgumentException("no"));
        perform(mockMvc, put("/api/v1/loadtest/summarizers/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(writeValueAsString(objectMapper, new UpdateSummarizerRequest(null, null, null, null, null))))
                .andExpect(status().isNotFound());

        doThrow(new IllegalArgumentException("no")).when(summarizerService).delete(id);
        perform(mockMvc, delete("/api/v1/loadtest/summarizers/{id}", id))
                .andExpect(status().isNotFound());
    }

    @Test
    void getAll_withEnabledFalse_usesAllBranch() {
        when(summarizerService.getAll()).thenReturn(List.of(sample()));
        perform(mockMvc, get("/api/v1/loadtest/summarizers").param("enabled", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("s1"));
    }
}
