package com.loadtest.app.controller;

import com.loadtest.app.dto.CreateSummarizerRequest;
import com.loadtest.app.dto.SummarizerModelDto;
import com.loadtest.app.dto.UpdateSummarizerRequest;
import com.loadtest.app.service.SummarizerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;

class SummarizerControllerDirectUnitTest {

    private SummarizerService service;
    private SummarizerController controller;

    @BeforeEach
    void setUp() {
        service = mock(SummarizerService.class);
        controller = new SummarizerController(service);
    }

    private SummarizerModelDto dto() {
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
    void coverAllBranchesDirectly() {
        CreateSummarizerRequest create = CreateSummarizerRequest.builder().name("s1").modelId("m").enabled(true).build();
        doReturn(dto()).when(service).create(any());
        assertThat(controller.create(create).getStatusCode().value()).isEqualTo(201);
        reset(service);
        doThrow(new IllegalArgumentException("bad")).when(service).create(any());
        assertThat(controller.create(create).getStatusCode().value()).isEqualTo(400);
        reset(service);
        doThrow(new RuntimeException("boom")).when(service).create(any());
        assertThat(controller.create(create).getStatusCode().value()).isEqualTo(500);

        reset(service);
        doReturn(List.of(dto())).when(service).getEnabled();
        assertThat(controller.getAll(true).getStatusCode().value()).isEqualTo(200);
        doReturn(List.of(dto())).when(service).getAll();
        assertThat(controller.getAll(false).getStatusCode().value()).isEqualTo(200);
        doThrow(new RuntimeException("boom")).when(service).getAll();
        assertThat(controller.getAll(null).getStatusCode().value()).isEqualTo(500);

        UUID id = UUID.randomUUID();
        reset(service);
        doReturn(dto()).when(service).getById(id);
        assertThat(controller.getById(id).getStatusCode().value()).isEqualTo(200);
        doThrow(new IllegalArgumentException("no")).when(service).getById(id);
        assertThat(controller.getById(id).getStatusCode().value()).isEqualTo(404);
        doThrow(new RuntimeException("boom")).when(service).getById(id);
        assertThat(controller.getById(id).getStatusCode().value()).isEqualTo(500);

        reset(service);
        doReturn(dto()).when(service).getByName("s1");
        assertThat(controller.getByName("s1").getStatusCode().value()).isEqualTo(200);
        doThrow(new IllegalArgumentException("no")).when(service).getByName("s1");
        assertThat(controller.getByName("s1").getStatusCode().value()).isEqualTo(404);
        doThrow(new RuntimeException("boom")).when(service).getByName("s1");
        assertThat(controller.getByName("s1").getStatusCode().value()).isEqualTo(500);

        UpdateSummarizerRequest upd = new UpdateSummarizerRequest();
        reset(service);
        doReturn(dto()).when(service).update(any(), any());
        assertThat(controller.update(id, upd).getStatusCode().value()).isEqualTo(200);
        doThrow(new IllegalArgumentException("no")).when(service).update(any(), any());
        assertThat(controller.update(id, upd).getStatusCode().value()).isEqualTo(404);
        doThrow(new RuntimeException("boom")).when(service).update(any(), any());
        assertThat(controller.update(id, upd).getStatusCode().value()).isEqualTo(500);

        reset(service);
        assertThat(controller.delete(id).getStatusCode().value()).isEqualTo(200);
        doThrow(new IllegalArgumentException("no")).when(service).delete(id);
        assertThat(controller.delete(id).getStatusCode().value()).isEqualTo(404);
        doThrow(new RuntimeException("boom")).when(service).delete(id);
        assertThat(controller.delete(id).getStatusCode().value()).isEqualTo(500);
    }
}

