package com.loadtest.app.controller;

import com.loadtest.app.dto.LoadTestToolDto;
import com.loadtest.app.dto.SummarizerModelDto;
import com.loadtest.app.service.CustomSummarizationPromptStore;
import com.loadtest.app.service.DockerExecutionProfileService;
import com.loadtest.app.service.LoadTestUploadService;
import com.loadtest.app.service.LoadTestToolService;
import com.loadtest.app.service.SummarizerService;
import com.loadtest.app.service.TestQueueService;
import com.loadtest.app.util.MetricsConfigParser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
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
import static com.loadtest.app.testsupport.MockMvcTestSupport.perform;

@WebMvcTest(controllers = LoadTestController.class)
@Import(LoadTestUploadService.class)
class LoadTestControllerWebMvcTest {

    private static final UUID TEST_PROFILE_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

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
    @MockBean
    private CustomSummarizationPromptStore customSummarizationPromptStore;

    @Test
    void metricsConfigSchema_returnsJsonFile() {
        perform(mockMvc, get("/api/v1/loadtest/metrics-config-schema"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"$schema\"")));
    }

    @Test
    void upload_rejectsBlankCommand() {
        var file = new MockMultipartFile("file", "x.js", "application/octet-stream", "code".getBytes());
        perform(mockMvc, multipart("/api/v1/loadtest/upload")
                        .file(file)
                        .param("tool", "K6")
                        .param("command", "   ")
                        .param("expectedDurationSeconds", "10")
                        .param("dockerExecutionProfileId", TEST_PROFILE_ID.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"));
    }

    @Test
    void upload_rejectsInvalidDuration() {
        var file = new MockMultipartFile("file", "x.js", "application/octet-stream", "code".getBytes());
        perform(mockMvc, multipart("/api/v1/loadtest/upload")
                        .file(file)
                        .param("tool", "K6")
                        .param("command", "run")
                        .param("expectedDurationSeconds", "0")
                        .param("dockerExecutionProfileId", TEST_PROFILE_ID.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("at least 1")));
    }

    @Test
    void upload_acceptsMinimalRequest() {
        when(loadTestToolService.getToolByName("K6")).thenReturn(k6Tool());
        UUID profileId = UUID.randomUUID();
        when(dockerExecutionProfileService.resolveProfileIdForUpload(TEST_PROFILE_ID.toString())).thenReturn(profileId);
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
        perform(mockMvc, multipart("/api/v1/loadtest/upload")
                        .file(file)
                        .param("tool", "k6")
                        .param("command", "k6 run {fileName}")
                        .param("expectedDurationSeconds", "30")
                        .param("dockerExecutionProfileId", TEST_PROFILE_ID.toString()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.taskId").value("new-task-id"))
                .andExpect(jsonPath("$.status").value("success"));
    }

    @Test
    void upload_toolNotFound() {
        when(loadTestToolService.getToolByName("K6")).thenThrow(new IllegalArgumentException("missing"));
        var file = new MockMultipartFile("file", "x.js", "application/octet-stream", "c".getBytes());
        perform(mockMvc, multipart("/api/v1/loadtest/upload")
                        .file(file)
                        .param("tool", "k6")
                        .param("command", "run")
                        .param("expectedDurationSeconds", "5")
                        .param("dockerExecutionProfileId", TEST_PROFILE_ID.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("not found")));
        verifyNoInteractions(testQueueService);
    }

    @Test
    void upload_toolDisabled() {
        when(loadTestToolService.getToolByName("K6")).thenReturn(new LoadTestToolDto(
                UUID.randomUUID(), "K6", "k6", List.of(".js"), false, OffsetDateTime.MIN, OffsetDateTime.MIN));
        var file = new MockMultipartFile("file", "x.js", "application/octet-stream", "c".getBytes());
        perform(mockMvc, multipart("/api/v1/loadtest/upload")
                        .file(file)
                        .param("tool", "k6")
                        .param("command", "run")
                        .param("expectedDurationSeconds", "5")
                        .param("dockerExecutionProfileId", TEST_PROFILE_ID.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("disabled")));
    }

    @Test
    void upload_summarizerNotFound() {
        stubEnabledK6Tool();
        when(summarizerService.getByName("missing")).thenThrow(new IllegalArgumentException("nope"));
        var file = new MockMultipartFile("file", "x.js", "application/octet-stream", "c".getBytes());
        perform(mockMvc, multipart("/api/v1/loadtest/upload")
                        .file(file)
                        .param("tool", "k6")
                        .param("command", "run")
                        .param("expectedDurationSeconds", "5")
                        .param("summarizer", "missing")
                        .param("dockerExecutionProfileId", TEST_PROFILE_ID.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("not found")));
    }

    @Test
    void upload_invalidMetricsJson() {
        stubEnabledK6Tool();
        when(dockerExecutionProfileService.resolveProfileIdForUpload(TEST_PROFILE_ID.toString())).thenReturn(UUID.randomUUID());
        when(metricsConfigParser.parseMetricsConfigRequests(anyString())).thenThrow(new IllegalArgumentException("bad"));
        var file = new MockMultipartFile("file", "x.js", "application/octet-stream", "c".getBytes());
        perform(mockMvc, multipart("/api/v1/loadtest/upload")
                        .file(file)
                        .param("tool", "k6")
                        .param("command", "run")
                        .param("expectedDurationSeconds", "5")
                        .param("metricsConfig", "{}")
                        .param("dockerExecutionProfileId", TEST_PROFILE_ID.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Invalid metrics")));
        verifyNoInteractions(testQueueService);
    }

    @Test
    void upload_metricsConfigParsedWithNullRequests_stillAccepted() {
        stubEnabledK6Tool();
        when(dockerExecutionProfileService.resolveProfileIdForUpload(TEST_PROFILE_ID.toString())).thenReturn(UUID.randomUUID());
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
        perform(mockMvc, multipart("/api/v1/loadtest/upload")
                        .file(file)
                        .param("tool", "k6")
                        .param("command", "run")
                        .param("expectedDurationSeconds", "5")
                        .param("metricsConfig", "{\"delaySeconds\":1}")
                        .param("dockerExecutionProfileId", TEST_PROFILE_ID.toString()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.taskId").value("tid-m"));
    }

    @Test
    void upload_rejectsFileWithoutExtension() {
        stubEnabledK6Tool();
        var file = new MockMultipartFile("file", "noext", "application/octet-stream", "c".getBytes());
        perform(mockMvc, multipart("/api/v1/loadtest/upload")
                        .file(file)
                        .param("tool", "k6")
                        .param("command", "run")
                        .param("expectedDurationSeconds", "5")
                        .param("dockerExecutionProfileId", TEST_PROFILE_ID.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("must have an extension")));
    }

    @Test
    void upload_rejectsExtensionMismatch() {
        when(loadTestToolService.getToolByName("K6")).thenReturn(k6Tool());
        var file = new MockMultipartFile("file", "test.py", "application/octet-stream", "c".getBytes());
        perform(mockMvc, multipart("/api/v1/loadtest/upload")
                        .file(file)
                        .param("tool", "k6")
                        .param("command", "run")
                        .param("expectedDurationSeconds", "5")
                        .param("dockerExecutionProfileId", TEST_PROFILE_ID.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("extension mismatch")));
    }

    @Test
    void upload_rejectsDisabledSummarizerAndExternalBadUrl() {
        stubEnabledK6Tool();
        when(summarizerService.getByName("route")).thenReturn(new SummarizerModelDto(
                UUID.randomUUID(), "route", "OPENAI", null, "m", null, false, OffsetDateTime.MIN, OffsetDateTime.MIN));
        var file = new MockMultipartFile("file", "x.js", "application/octet-stream", "c".getBytes());
        perform(mockMvc, multipart("/api/v1/loadtest/upload")
                        .file(file)
                        .param("tool", "k6")
                        .param("command", "run")
                        .param("expectedDurationSeconds", "5")
                        .param("summarizer", "route")
                        .param("dockerExecutionProfileId", TEST_PROFILE_ID.toString()))
                .andExpect(status().isBadRequest());

        when(summarizerService.getByName("route")).thenReturn(new SummarizerModelDto(
                UUID.randomUUID(), "route", "EXTERNAL", "ftp://bad", "m", null, true, OffsetDateTime.MIN, OffsetDateTime.MIN));
        perform(mockMvc, multipart("/api/v1/loadtest/upload")
                        .file(file)
                        .param("tool", "k6")
                        .param("command", "run")
                        .param("expectedDurationSeconds", "5")
                        .param("summarizer", "route")
                        .param("dockerExecutionProfileId", TEST_PROFILE_ID.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("http://")));
    }

    @Test
    void upload_rejectsMissingDockerProfile() {
        stubEnabledK6Tool();
        var file = new MockMultipartFile("file", "x.js", "application/octet-stream", "c".getBytes());
        perform(mockMvc, multipart("/api/v1/loadtest/upload")
                        .file(file)
                        .param("tool", "k6")
                        .param("command", "run")
                        .param("expectedDurationSeconds", "5"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(testQueueService);
    }

    @Test
    void upload_rejectsSummarizerWithNullEnabled() {
        stubEnabledK6Tool();
        when(summarizerService.getByName("route")).thenReturn(new SummarizerModelDto(
                UUID.randomUUID(), "route", "OPENAI", null, "m", null, null, OffsetDateTime.MIN, OffsetDateTime.MIN));
        var file = new MockMultipartFile("file", "x.js", "application/octet-stream", "c".getBytes());
        perform(mockMvc, multipart("/api/v1/loadtest/upload")
                        .file(file)
                        .param("tool", "k6")
                        .param("command", "run")
                        .param("expectedDurationSeconds", "5")
                        .param("summarizer", "route")
                        .param("dockerExecutionProfileId", TEST_PROFILE_ID.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void upload_externalSummarizerWithoutBaseUrl() {
        stubEnabledK6Tool();
        when(summarizerService.getByName("route")).thenReturn(new SummarizerModelDto(
                UUID.randomUUID(), "route", "EXTERNAL", " ", "m", null, true, OffsetDateTime.MIN, OffsetDateTime.MIN));
        var file = new MockMultipartFile("file", "x.js", "application/octet-stream", "c".getBytes());
        perform(mockMvc, multipart("/api/v1/loadtest/upload")
                        .file(file)
                        .param("tool", "k6")
                        .param("command", "run")
                        .param("expectedDurationSeconds", "5")
                        .param("summarizer", "route")
                        .param("dockerExecutionProfileId", TEST_PROFILE_ID.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("EXTERNAL требует полный URL")));
    }

    @Test
    void upload_returns500OnUnexpectedException() {
        stubEnabledK6Tool();
        when(dockerExecutionProfileService.resolveProfileIdForUpload(TEST_PROFILE_ID.toString())).thenThrow(new RuntimeException("boom"));
        var file = new MockMultipartFile("file", "x.js", "application/octet-stream", "c".getBytes());
        perform(mockMvc, multipart("/api/v1/loadtest/upload")
                        .file(file)
                        .param("tool", "k6")
                        .param("command", "run")
                        .param("expectedDurationSeconds", "5")
                        .param("dockerExecutionProfileId", TEST_PROFILE_ID.toString()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Failed to upload file")));
    }

    @Test
    void upload_returns400OnIllegalArgumentException() {
        stubEnabledK6Tool();
        when(dockerExecutionProfileService.resolveProfileIdForUpload(TEST_PROFILE_ID.toString())).thenReturn(UUID.randomUUID());
        when(testQueueService.enqueueTest(
                eq("K6"), eq("x.js"), anyString(), eq("run"), eq(5),
                isNull(), isNull(), isNull(), any(UUID.class)))
                .thenThrow(new IllegalArgumentException("bad request"));
        var file = new MockMultipartFile("file", "x.js", "application/octet-stream", "c".getBytes());
        perform(mockMvc, multipart("/api/v1/loadtest/upload")
                        .file(file)
                        .param("tool", "k6")
                        .param("command", "run")
                        .param("expectedDurationSeconds", "5")
                        .param("dockerExecutionProfileId", TEST_PROFILE_ID.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Invalid tool")));
    }

    @Test
    void upload_rejectsInvalidFileBeforeEnqueue() {
        stubEnabledK6Tool();
        var file = new MockMultipartFile("file", "x.js", "application/octet-stream", new byte[0]);
        perform(mockMvc, multipart("/api/v1/loadtest/upload")
                        .file(file)
                        .param("tool", "k6")
                        .param("command", "run")
                        .param("expectedDurationSeconds", "5")
                        .param("dockerExecutionProfileId", TEST_PROFILE_ID.toString()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(testQueueService);
    }

    @Test
    void upload_withSummarizer_ok() {
        stubEnabledK6Tool();
        when(summarizerService.getByName("route")).thenReturn(new SummarizerModelDto(
                UUID.randomUUID(), "route", "OPENAI", null, "m", null, true, OffsetDateTime.MIN, OffsetDateTime.MIN));
        UUID profileId = UUID.randomUUID();
        when(dockerExecutionProfileService.resolveProfileIdForUpload(TEST_PROFILE_ID.toString())).thenReturn(profileId);
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
        perform(mockMvc, multipart("/api/v1/loadtest/upload")
                        .file(file)
                        .param("tool", "k6")
                        .param("command", "run")
                        .param("expectedDurationSeconds", "5")
                        .param("summarizer", "route")
                        .param("dockerExecutionProfileId", TEST_PROFILE_ID.toString()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.taskId").value("tid"));
    }

    private void stubEnabledK6Tool() {
        when(loadTestToolService.getToolByName("K6")).thenReturn(k6Tool());
    }

    private static LoadTestToolDto k6Tool() {
        return new LoadTestToolDto(
                UUID.randomUUID(), "K6", "k6", List.of(".js"), true, OffsetDateTime.MIN, OffsetDateTime.MIN);
    }
}
