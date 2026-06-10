package com.loadtest.app.controller;

import com.loadtest.app.dto.LoadTestToolDto;
import com.loadtest.app.service.CustomSummarizationPromptStore;
import com.loadtest.app.service.DockerExecutionProfileService;
import com.loadtest.app.service.LoadTestToolService;
import com.loadtest.app.service.LoadTestUploadService;
import com.loadtest.app.service.SummarizerService;
import com.loadtest.app.service.TestQueueService;
import com.loadtest.app.util.MetricsConfigParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.loadtest.app.testsupport.MockMvcTestSupport.perform;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = LoadTestController.class)
@Import(LoadTestUploadService.class)
class LoadTestUploadMaxSizeWebMvcTest {

    @DynamicPropertySource
    static void uploadLimits(DynamicPropertyRegistry registry) {
        registry.add("loadtest.upload.max-scenario-file-size-bytes", () -> 64);
    }

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

    private static final UUID PROFILE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @BeforeEach
    void setUp() {
        when(dockerExecutionProfileService.resolveProfileIdForUpload(PROFILE_ID.toString())).thenReturn(PROFILE_ID);
    }

    @Test
    void upload_rejectsFileExceedingMaxScenarioSize() {
        when(loadTestToolService.getToolByName("K6")).thenReturn(k6Tool());
        byte[] oversized = new byte[128];
        var file = new MockMultipartFile("file", "load.js", "application/octet-stream", oversized);
        perform(mockMvc, multipart("/api/v1/loadtest/upload")
                        .file(file)
                        .param("tool", "K6")
                        .param("command", "k6 run {fileName}")
                        .param("expectedDurationSeconds", "10")
                        .param("dockerExecutionProfileId", PROFILE_ID.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("exceeds the maximum")));
        verifyNoInteractions(testQueueService);
    }

    private static LoadTestToolDto k6Tool() {
        return new LoadTestToolDto(
                UUID.randomUUID(),
                "K6",
                "grafana/k6:latest",
                List.of("js"),
                true,
                OffsetDateTime.now(),
                OffsetDateTime.now());
    }
}
