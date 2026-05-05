package com.loadtest.app.controller;

import com.loadtest.app.dto.LoadTestToolDto;
import com.loadtest.app.dto.SummarizerModelDto;
import com.loadtest.app.service.DockerExecutionProfileService;
import com.loadtest.app.service.LoadTestToolService;
import com.loadtest.app.service.SummarizerService;
import com.loadtest.app.service.TestQueueService;
import com.loadtest.app.util.MetricsConfigParser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = LoadTestController.class)
class LoadTestControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TestQueueService testQueueService;
    @MockBean
    private MetricsConfigParser metricsConfigParser;
    @MockBean
    private LoadTestToolService loadTestToolService;
    @MockBean
    private SummarizerService summarizerService;
    @MockBean
    private DockerExecutionProfileService dockerExecutionProfileService;

    @Test
    void metricsConfigSchema_returnsJsonFile() throws Exception {
        mockMvc.perform(get("/api/v1/loadtest/metrics-config-schema"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"$schema\"")));
    }

    @Test
    void upload_rejectsBlankCommand() throws Exception {
        var file = new MockMultipartFile("file", "x.js", "application/octet-stream", "code".getBytes());
        mockMvc.perform(multipart("/api/v1/loadtest/upload")
                        .file(file)
                        .param("tool", "K6")
                        .param("command", "   ")
                        .param("expectedDurationSeconds", "10"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"));
    }

    @Test
    void upload_rejectsInvalidDuration() throws Exception {
        var file = new MockMultipartFile("file", "x.js", "application/octet-stream", "code".getBytes());
        mockMvc.perform(multipart("/api/v1/loadtest/upload")
                        .file(file)
                        .param("tool", "K6")
                        .param("command", "run")
                        .param("expectedDurationSeconds", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("at least 1")));
    }

    @Test
    void upload_acceptsMinimalRequest() throws Exception {
        when(loadTestToolService.getToolByName("K6")).thenReturn(LoadTestToolDto.builder()
                .id(UUID.randomUUID())
                .name("K6")
                .dockerImage("k6")
                .fileExtensions(List.of(".js"))
                .enabled(true)
                .createdAt(OffsetDateTime.MIN)
                .updatedAt(OffsetDateTime.MIN)
                .build());
        UUID profileId = UUID.randomUUID();
        when(dockerExecutionProfileService.resolveProfileIdForUpload(isNull())).thenReturn(profileId);
        when(testQueueService.enqueueTest(
                        eq("K6"),
                        eq("load.js"),
                        anyString(),
                        eq("k6 run {fileName}"),
                        eq(30),
                        isNull(),
                        isNull(),
                        isNull(),
                        eq(profileId)))
                .thenReturn("new-task-id");

        var file = new MockMultipartFile("file", "load.js", "application/octet-stream", "export default function() {}".getBytes());
        mockMvc.perform(multipart("/api/v1/loadtest/upload")
                        .file(file)
                        .param("tool", "k6")
                        .param("command", "k6 run {fileName}")
                        .param("expectedDurationSeconds", "30"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.taskId").value("new-task-id"))
                .andExpect(jsonPath("$.status").value("success"));
    }

    @Test
    void upload_toolNotFound() throws Exception {
        when(loadTestToolService.getToolByName("K6")).thenThrow(new IllegalArgumentException("missing"));
        var file = new MockMultipartFile("file", "x.js", "application/octet-stream", "c".getBytes());
        mockMvc.perform(multipart("/api/v1/loadtest/upload")
                        .file(file)
                        .param("tool", "k6")
                        .param("command", "run")
                        .param("expectedDurationSeconds", "5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("not found")));
        verifyNoInteractions(testQueueService);
    }

    @Test
    void upload_toolDisabled() throws Exception {
        when(loadTestToolService.getToolByName("K6")).thenReturn(LoadTestToolDto.builder()
                .id(UUID.randomUUID())
                .name("K6")
                .dockerImage("k6")
                .fileExtensions(List.of(".js"))
                .enabled(false)
                .createdAt(OffsetDateTime.MIN)
                .updatedAt(OffsetDateTime.MIN)
                .build());
        var file = new MockMultipartFile("file", "x.js", "application/octet-stream", "c".getBytes());
        mockMvc.perform(multipart("/api/v1/loadtest/upload")
                        .file(file)
                        .param("tool", "k6")
                        .param("command", "run")
                        .param("expectedDurationSeconds", "5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("disabled")));
    }

    @Test
    void upload_summarizerNotFound() throws Exception {
        stubEnabledK6Tool();
        when(summarizerService.getByName("missing")).thenThrow(new IllegalArgumentException("nope"));
        var file = new MockMultipartFile("file", "x.js", "application/octet-stream", "c".getBytes());
        mockMvc.perform(multipart("/api/v1/loadtest/upload")
                        .file(file)
                        .param("tool", "k6")
                        .param("command", "run")
                        .param("expectedDurationSeconds", "5")
                        .param("summarizer", "missing"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("not found")));
    }

    @Test
    void upload_invalidMetricsJson() throws Exception {
        stubEnabledK6Tool();
        when(dockerExecutionProfileService.resolveProfileIdForUpload(isNull())).thenReturn(UUID.randomUUID());
        when(metricsConfigParser.parseMetricsConfigRequests(anyString())).thenThrow(new IllegalArgumentException("bad"));
        var file = new MockMultipartFile("file", "x.js", "application/octet-stream", "c".getBytes());
        mockMvc.perform(multipart("/api/v1/loadtest/upload")
                        .file(file)
                        .param("tool", "k6")
                        .param("command", "run")
                        .param("expectedDurationSeconds", "5")
                        .param("metricsConfig", "{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Invalid metrics")));
        verifyNoInteractions(testQueueService);
    }

    @Test
    void upload_metricsConfigParsedWithNullRequests_stillAccepted() throws Exception {
        stubEnabledK6Tool();
        when(dockerExecutionProfileService.resolveProfileIdForUpload(isNull())).thenReturn(UUID.randomUUID());
        when(metricsConfigParser.parseMetricsConfigRequests(anyString()))
                .thenReturn(new com.loadtest.app.dto.TestTaskMessage.MetricsConfig(1, null));
        when(testQueueService.enqueueTest(
                        eq("K6"),
                        eq("x.js"),
                        anyString(),
                        eq("run"),
                        eq(5),
                        any(),
                        eq("{\"delaySeconds\":1}"),
                        isNull(),
                        any(UUID.class)))
                .thenReturn("tid-m");
        var file = new MockMultipartFile("file", "x.js", "application/octet-stream", "c".getBytes());
        mockMvc.perform(multipart("/api/v1/loadtest/upload")
                        .file(file)
                        .param("tool", "k6")
                        .param("command", "run")
                        .param("expectedDurationSeconds", "5")
                        .param("metricsConfig", "{\"delaySeconds\":1}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.taskId").value("tid-m"));
    }

    @Test
    void upload_rejectsFileWithoutExtension() throws Exception {
        stubEnabledK6Tool();
        var file = new MockMultipartFile("file", "noext", "application/octet-stream", "c".getBytes());
        mockMvc.perform(multipart("/api/v1/loadtest/upload")
                        .file(file)
                        .param("tool", "k6")
                        .param("command", "run")
                        .param("expectedDurationSeconds", "5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("must have an extension")));
    }

    @Test
    void upload_rejectsExtensionMismatch() throws Exception {
        when(loadTestToolService.getToolByName("K6")).thenReturn(LoadTestToolDto.builder()
                .id(UUID.randomUUID())
                .name("K6")
                .dockerImage("k6")
                .fileExtensions(List.of(".js"))
                .enabled(true)
                .createdAt(OffsetDateTime.MIN)
                .updatedAt(OffsetDateTime.MIN)
                .build());
        var file = new MockMultipartFile("file", "test.py", "application/octet-stream", "c".getBytes());
        mockMvc.perform(multipart("/api/v1/loadtest/upload")
                        .file(file)
                        .param("tool", "k6")
                        .param("command", "run")
                        .param("expectedDurationSeconds", "5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("extension mismatch")));
    }

    @Test
    void upload_rejectsDisabledSummarizerAndExternalBadUrl() throws Exception {
        stubEnabledK6Tool();
        when(summarizerService.getByName("route")).thenReturn(SummarizerModelDto.builder()
                .id(UUID.randomUUID())
                .name("route")
                .provider("OPENAI")
                .modelId("m")
                .enabled(false)
                .createdAt(OffsetDateTime.MIN)
                .updatedAt(OffsetDateTime.MIN)
                .build());
        var file = new MockMultipartFile("file", "x.js", "application/octet-stream", "c".getBytes());
        mockMvc.perform(multipart("/api/v1/loadtest/upload")
                        .file(file)
                        .param("tool", "k6")
                        .param("command", "run")
                        .param("expectedDurationSeconds", "5")
                        .param("summarizer", "route"))
                .andExpect(status().isBadRequest());

        when(summarizerService.getByName("route")).thenReturn(SummarizerModelDto.builder()
                .id(UUID.randomUUID())
                .name("route")
                .provider("EXTERNAL")
                .baseUrl("ftp://bad")
                .modelId("m")
                .enabled(true)
                .createdAt(OffsetDateTime.MIN)
                .updatedAt(OffsetDateTime.MIN)
                .build());
        mockMvc.perform(multipart("/api/v1/loadtest/upload")
                        .file(file)
                        .param("tool", "k6")
                        .param("command", "run")
                        .param("expectedDurationSeconds", "5")
                        .param("summarizer", "route"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("http://")));
    }

    @Test
    void upload_rejectsSummarizerWithNullEnabled() throws Exception {
        stubEnabledK6Tool();
        when(summarizerService.getByName("route")).thenReturn(SummarizerModelDto.builder()
                .id(UUID.randomUUID())
                .name("route")
                .provider("OPENAI")
                .modelId("m")
                .enabled(null)
                .createdAt(OffsetDateTime.MIN)
                .updatedAt(OffsetDateTime.MIN)
                .build());
        var file = new MockMultipartFile("file", "x.js", "application/octet-stream", "c".getBytes());
        mockMvc.perform(multipart("/api/v1/loadtest/upload")
                        .file(file)
                        .param("tool", "k6")
                        .param("command", "run")
                        .param("expectedDurationSeconds", "5")
                        .param("summarizer", "route"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void upload_externalSummarizerWithoutBaseUrl() throws Exception {
        stubEnabledK6Tool();
        when(summarizerService.getByName("route")).thenReturn(SummarizerModelDto.builder()
                .id(UUID.randomUUID())
                .name("route")
                .provider("EXTERNAL")
                .baseUrl(" ")
                .modelId("m")
                .enabled(true)
                .createdAt(OffsetDateTime.MIN)
                .updatedAt(OffsetDateTime.MIN)
                .build());
        var file = new MockMultipartFile("file", "x.js", "application/octet-stream", "c".getBytes());
        mockMvc.perform(multipart("/api/v1/loadtest/upload")
                        .file(file)
                        .param("tool", "k6")
                        .param("command", "run")
                        .param("expectedDurationSeconds", "5")
                        .param("summarizer", "route"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("EXTERNAL требует полный URL")));
    }

    @Test
    void upload_returns500OnUnexpectedException() throws Exception {
        stubEnabledK6Tool();
        when(dockerExecutionProfileService.resolveProfileIdForUpload(isNull())).thenThrow(new RuntimeException("boom"));
        var file = new MockMultipartFile("file", "x.js", "application/octet-stream", "c".getBytes());
        mockMvc.perform(multipart("/api/v1/loadtest/upload")
                        .file(file)
                        .param("tool", "k6")
                        .param("command", "run")
                        .param("expectedDurationSeconds", "5"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Failed to upload file")));
    }

    @Test
    void upload_returns400OnIllegalArgumentException() throws Exception {
        stubEnabledK6Tool();
        when(dockerExecutionProfileService.resolveProfileIdForUpload(isNull())).thenReturn(UUID.randomUUID());
        when(testQueueService.enqueueTest(
                eq("K6"), eq("x.js"), anyString(), eq("run"), eq(5),
                isNull(), isNull(), isNull(), any(UUID.class)))
                .thenThrow(new IllegalArgumentException("bad request"));
        var file = new MockMultipartFile("file", "x.js", "application/octet-stream", "c".getBytes());
        mockMvc.perform(multipart("/api/v1/loadtest/upload")
                        .file(file)
                        .param("tool", "k6")
                        .param("command", "run")
                        .param("expectedDurationSeconds", "5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Invalid tool")));
    }

    @Test
    void upload_rejectsInvalidFileBeforeEnqueue() throws Exception {
        stubEnabledK6Tool();
        var file = new MockMultipartFile("file", "x.js", "application/octet-stream", new byte[0]);
        mockMvc.perform(multipart("/api/v1/loadtest/upload")
                        .file(file)
                        .param("tool", "k6")
                        .param("command", "run")
                        .param("expectedDurationSeconds", "5"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(testQueueService);
    }

    @Test
    void upload_withSummarizer_ok() throws Exception {
        stubEnabledK6Tool();
        when(summarizerService.getByName("route")).thenReturn(SummarizerModelDto.builder()
                .id(UUID.randomUUID())
                .name("route")
                .provider("OPENAI")
                .modelId("m")
                .enabled(true)
                .createdAt(OffsetDateTime.MIN)
                .updatedAt(OffsetDateTime.MIN)
                .build());
        UUID profileId = UUID.randomUUID();
        when(dockerExecutionProfileService.resolveProfileIdForUpload(isNull())).thenReturn(profileId);
        when(testQueueService.enqueueTest(
                        eq("K6"),
                        eq("x.js"),
                        anyString(),
                        eq("run"),
                        eq(5),
                        isNull(),
                        isNull(),
                        eq("route"),
                        eq(profileId)))
                .thenReturn("tid");

        var file = new MockMultipartFile("file", "x.js", "application/octet-stream", "c".getBytes());
        mockMvc.perform(multipart("/api/v1/loadtest/upload")
                        .file(file)
                        .param("tool", "k6")
                        .param("command", "run")
                        .param("expectedDurationSeconds", "5")
                        .param("summarizer", "route"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.taskId").value("tid"));
    }

    private void stubEnabledK6Tool() {
        when(loadTestToolService.getToolByName("K6")).thenReturn(LoadTestToolDto.builder()
                .id(UUID.randomUUID())
                .name("K6")
                .dockerImage("k6")
                .fileExtensions(List.of(".js"))
                .enabled(true)
                .createdAt(OffsetDateTime.MIN)
                .updatedAt(OffsetDateTime.MIN)
                .build());
    }
}
