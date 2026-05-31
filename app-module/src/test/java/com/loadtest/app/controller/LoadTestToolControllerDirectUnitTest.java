package com.loadtest.app.controller;

import com.loadtest.app.dto.CreateLoadTestToolRequest;
import com.loadtest.app.dto.LoadTestToolDto;
import com.loadtest.app.dto.UpdateLoadTestToolRequest;
import com.loadtest.app.service.LoadTestToolService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;

class LoadTestToolControllerDirectUnitTest {

    private LoadTestToolService service;
    private LoadTestToolController controller;

    @BeforeEach
    void setUp() {
        service = mock(LoadTestToolService.class);
        controller = new LoadTestToolController(service);
    }

    private LoadTestToolDto dto() {
        return new LoadTestToolDto(
                UUID.randomUUID(),
                "K6",
                "k6",
                List.of(".js"),
                true,
                OffsetDateTime.MIN,
                OffsetDateTime.MIN);
    }

    @Test
    void coverAllControllerBranchesDirectly() {
        CreateLoadTestToolRequest create = new CreateLoadTestToolRequest("K6", "k6", List.of(".js"), true);
        doReturn(dto()).when(service).createTool(any());
        ResponseEntity<Map<String, Object>> cOk = controller.createTool(create);
        assertThat(cOk.getStatusCode().value()).isEqualTo(201);
        reset(service);
        doThrow(new IllegalArgumentException("bad")).when(service).createTool(any());
        assertThat(controller.createTool(create).getStatusCode().value()).isEqualTo(400);
        reset(service);
        doThrow(new RuntimeException("boom")).when(service).createTool(any());
        assertThat(controller.createTool(create).getStatusCode().value()).isEqualTo(500);

        reset(service);
        doReturn(List.of(dto())).when(service).getEnabledTools();
        assertThat(controller.getAllTools(true).getStatusCode().value()).isEqualTo(200);
        doReturn(List.of(dto())).when(service).getAllTools();
        assertThat(controller.getAllTools(false).getStatusCode().value()).isEqualTo(200);
        doThrow(new RuntimeException("boom")).when(service).getAllTools();
        assertThat(controller.getAllTools(null).getStatusCode().value()).isEqualTo(500);

        UUID id = UUID.randomUUID();
        reset(service);
        doReturn(dto()).when(service).getToolById(id);
        assertThat(controller.getToolById(id).getStatusCode().value()).isEqualTo(200);
        doThrow(new IllegalArgumentException("no")).when(service).getToolById(id);
        assertThat(controller.getToolById(id).getStatusCode().value()).isEqualTo(404);
        doThrow(new RuntimeException("boom")).when(service).getToolById(id);
        assertThat(controller.getToolById(id).getStatusCode().value()).isEqualTo(500);

        reset(service);
        doReturn(dto()).when(service).getToolByName("k6");
        assertThat(controller.getToolByName("k6").getStatusCode().value()).isEqualTo(200);
        doThrow(new IllegalArgumentException("no")).when(service).getToolByName("k6");
        assertThat(controller.getToolByName("k6").getStatusCode().value()).isEqualTo(404);
        doThrow(new RuntimeException("boom")).when(service).getToolByName("k6");
        assertThat(controller.getToolByName("k6").getStatusCode().value()).isEqualTo(500);

        UpdateLoadTestToolRequest upd = new UpdateLoadTestToolRequest(null, null, null);
        reset(service);
        doReturn(dto()).when(service).updateTool(any(), any());
        assertThat(controller.updateTool(id, upd).getStatusCode().value()).isEqualTo(200);
        doThrow(new IllegalArgumentException("no")).when(service).updateTool(any(), any());
        assertThat(controller.updateTool(id, upd).getStatusCode().value()).isEqualTo(404);
        doThrow(new RuntimeException("boom")).when(service).updateTool(any(), any());
        assertThat(controller.updateTool(id, upd).getStatusCode().value()).isEqualTo(500);

        reset(service);
        assertThat(controller.deleteTool(id).getStatusCode().value()).isEqualTo(200);
        doThrow(new IllegalArgumentException("no")).when(service).deleteTool(id);
        assertThat(controller.deleteTool(id).getStatusCode().value()).isEqualTo(404);
    }
}
