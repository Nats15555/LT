package com.loadtest.app.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadtest.app.dto.CreateLoadTestToolRequest;
import com.loadtest.app.dto.LoadTestToolDto;
import com.loadtest.app.dto.UpdateLoadTestToolRequest;
import com.loadtest.app.service.LoadTestToolService;
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

@WebMvcTest(controllers = LoadTestToolController.class)
class LoadTestToolControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private LoadTestToolService toolService;

    private LoadTestToolDto tool(String name) {
        return LoadTestToolDto.builder()
                .id(UUID.randomUUID())
                .name(name)
                .dockerImage("img")
                .fileExtensions(List.of(".js"))
                .enabled(true)
                .createdAt(OffsetDateTime.MIN)
                .updatedAt(OffsetDateTime.MIN)
                .build();
    }

    @Test
    void crudAndListFilters() throws Exception {
        CreateLoadTestToolRequest create = CreateLoadTestToolRequest.builder()
                .name("K6")
                .dockerImage("k6:latest")
                .fileExtensions(List.of(".js"))
                .enabled(true)
                .build();
        when(toolService.createTool(any())).thenReturn(tool("K6"));

        mockMvc.perform(post("/api/v1/loadtest/tools")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(create)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("K6"));

        when(toolService.getAllTools()).thenReturn(List.of(tool("K6")));
        mockMvc.perform(get("/api/v1/loadtest/tools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("K6"));

        when(toolService.getEnabledTools()).thenReturn(List.of());
        mockMvc.perform(get("/api/v1/loadtest/tools").param("enabled", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());

        UUID id = UUID.randomUUID();
        when(toolService.getToolById(id)).thenReturn(tool("JMETER"));
        mockMvc.perform(get("/api/v1/loadtest/tools/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("JMETER"));

        when(toolService.getToolByName("k6")).thenReturn(tool("K6"));
        mockMvc.perform(get("/api/v1/loadtest/tools/name/{name}", "k6"))
                .andExpect(status().isOk());

        UpdateLoadTestToolRequest upd = new UpdateLoadTestToolRequest();
        upd.setEnabled(false);
        when(toolService.updateTool(eq(id), any())).thenReturn(tool("JMETER"));
        mockMvc.perform(put("/api/v1/loadtest/tools/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(upd)))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/loadtest/tools/{id}", id))
                .andExpect(status().isOk());
    }

    @Test
    void getTool_notFound() throws Exception {
        UUID id = UUID.randomUUID();
        when(toolService.getToolById(id)).thenThrow(new IllegalArgumentException("missing"));
        mockMvc.perform(get("/api/v1/loadtest/tools/{id}", id))
                .andExpect(status().isNotFound());
    }

    @Test
    void controllerRuntimeExceptions_return500() throws Exception {
        when(toolService.getAllTools()).thenThrow(new RuntimeException("db down"));
        mockMvc.perform(get("/api/v1/loadtest/tools"))
                .andExpect(status().isInternalServerError());

        when(toolService.getToolByName("x")).thenThrow(new RuntimeException("boom"));
        mockMvc.perform(get("/api/v1/loadtest/tools/name/{name}", "x"))
                .andExpect(status().isInternalServerError());

        UUID id = UUID.randomUUID();
        when(toolService.updateTool(eq(id), any())).thenThrow(new RuntimeException("boom"));
        mockMvc.perform(put("/api/v1/loadtest/tools/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateLoadTestToolRequest())))
                .andExpect(status().isInternalServerError());

        doThrow(new RuntimeException("boom")).when(toolService).deleteTool(id);
        mockMvc.perform(delete("/api/v1/loadtest/tools/{id}", id))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void createAndNotFoundBranches() throws Exception {
        CreateLoadTestToolRequest create = CreateLoadTestToolRequest.builder()
                .name("K6")
                .dockerImage("k6:latest")
                .fileExtensions(List.of(".js"))
                .enabled(true)
                .build();
        when(toolService.createTool(any())).thenThrow(new IllegalArgumentException("bad"));
        mockMvc.perform(post("/api/v1/loadtest/tools")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(create)))
                .andExpect(status().isBadRequest());

        UUID id = UUID.randomUUID();
        when(toolService.getToolByName("missing")).thenThrow(new IllegalArgumentException("no"));
        mockMvc.perform(get("/api/v1/loadtest/tools/name/{name}", "missing"))
                .andExpect(status().isNotFound());

        when(toolService.updateTool(eq(id), any())).thenThrow(new IllegalArgumentException("no"));
        mockMvc.perform(put("/api/v1/loadtest/tools/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateLoadTestToolRequest())))
                .andExpect(status().isNotFound());

        doThrow(new IllegalArgumentException("no")).when(toolService).deleteTool(id);
        mockMvc.perform(delete("/api/v1/loadtest/tools/{id}", id))
                .andExpect(status().isNotFound());
    }

    @Test
    void getAll_withEnabledFalse_usesAllBranch() throws Exception {
        when(toolService.getAllTools()).thenReturn(List.of(tool("K6")));
        mockMvc.perform(get("/api/v1/loadtest/tools").param("enabled", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("K6"));
    }

    @Test
    void createAndGetById_runtime500Branches() throws Exception {
        CreateLoadTestToolRequest create = CreateLoadTestToolRequest.builder()
                .name("K6")
                .dockerImage("k6:latest")
                .fileExtensions(List.of(".js"))
                .enabled(true)
                .build();
        when(toolService.createTool(any())).thenThrow(new RuntimeException("boom"));
        mockMvc.perform(post("/api/v1/loadtest/tools")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(create)))
                .andExpect(status().isInternalServerError());

        UUID id = UUID.randomUUID();
        when(toolService.getToolById(id)).thenThrow(new RuntimeException("boom"));
        mockMvc.perform(get("/api/v1/loadtest/tools/{id}", id))
                .andExpect(status().isInternalServerError());
    }
}
