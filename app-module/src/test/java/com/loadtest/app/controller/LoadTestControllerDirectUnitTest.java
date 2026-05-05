package com.loadtest.app.controller;

import com.loadtest.app.dto.LoadTestToolDto;
import com.loadtest.app.dto.SummarizerModelDto;
import com.loadtest.app.service.DockerExecutionProfileService;
import com.loadtest.app.service.LoadTestToolService;
import com.loadtest.app.service.SummarizerService;
import com.loadtest.app.service.TestQueueService;
import com.loadtest.app.util.MetricsConfigParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LoadTestControllerDirectUnitTest {

    private TestQueueService testQueueService;
    private MetricsConfigParser metricsConfigParser;
    private LoadTestToolService loadTestToolService;
    private SummarizerService summarizerService;
    private DockerExecutionProfileService dockerExecutionProfileService;
    private LoadTestController controller;

    @BeforeEach
    void setUp() {
        testQueueService = mock(TestQueueService.class);
        metricsConfigParser = mock(MetricsConfigParser.class);
        loadTestToolService = mock(LoadTestToolService.class);
        summarizerService = mock(SummarizerService.class);
        dockerExecutionProfileService = mock(DockerExecutionProfileService.class);
        controller = new LoadTestController(
                testQueueService,
                metricsConfigParser,
                loadTestToolService,
                summarizerService,
                dockerExecutionProfileService);
    }

    @Test
    void upload_catchesIOExceptionBranch() throws Exception {
        MultipartFile bad = mock(MultipartFile.class);
        when(bad.getOriginalFilename()).thenReturn("x.js");
        when(loadTestToolService.getToolByName("K6")).thenReturn(LoadTestToolDto.builder()
                .id(UUID.randomUUID()).name("K6").dockerImage("k6").fileExtensions(List.of(".js")).enabled(true)
                .createdAt(OffsetDateTime.MIN).updatedAt(OffsetDateTime.MIN).build());
        when(bad.getBytes()).thenThrow(new IOException("io"));

        ResponseEntity<Map<String, String>> resp = controller.uploadTestFile(
                bad, "k6", "run", 5, null, null, null);
        assertThat(resp.getStatusCode().value()).isEqualTo(500);
    }

    @Test
    void upload_catchesGenericExceptionBranch() throws Exception {
        MultipartFile good = mock(MultipartFile.class);
        when(good.getOriginalFilename()).thenReturn("x.js");
        when(good.getBytes()).thenReturn("ok".getBytes());
        when(loadTestToolService.getToolByName("K6")).thenReturn(LoadTestToolDto.builder()
                .id(UUID.randomUUID()).name("K6").dockerImage("k6").fileExtensions(List.of(".js")).enabled(true)
                .createdAt(OffsetDateTime.MIN).updatedAt(OffsetDateTime.MIN).build());
        when(dockerExecutionProfileService.resolveProfileIdForUpload(isNull())).thenReturn(UUID.randomUUID());
        when(testQueueService.enqueueTest(anyString(), anyString(), anyString(), anyString(), any(),
                any(), any(), any(), any())).thenThrow(new RuntimeException("boom"));

        ResponseEntity<Map<String, String>> resp = controller.uploadTestFile(
                good, "k6", "run", 5, null, null, null);
        assertThat(resp.getStatusCode().value()).isEqualTo(500);
    }

    @Test
    void upload_rejectsNullCommand_andNullDuration() {
        MultipartFile file = mock(MultipartFile.class);
        ResponseEntity<Map<String, String>> noCmd = controller.uploadTestFile(
                file, "k6", null, 5, null, null, null);
        assertThat(noCmd.getStatusCode().value()).isEqualTo(400);

        ResponseEntity<Map<String, String>> noDur = controller.uploadTestFile(
                file, "k6", "run", null, null, null, null);
        assertThat(noDur.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void upload_rejectsExternalSummarizerWithNullAndInvalidBaseUrl() throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn("x.js");
        when(loadTestToolService.getToolByName("K6")).thenReturn(LoadTestToolDto.builder()
                .id(UUID.randomUUID()).name("K6").dockerImage("k6").fileExtensions(List.of(".js")).enabled(true)
                .createdAt(OffsetDateTime.MIN).updatedAt(OffsetDateTime.MIN).build());

        when(summarizerService.getByName("ext")).thenReturn(SummarizerModelDto.builder()
                .id(UUID.randomUUID()).name("ext").provider("EXTERNAL").modelId("m").baseUrl(null).enabled(true)
                .createdAt(OffsetDateTime.MIN).updatedAt(OffsetDateTime.MIN).build());
        ResponseEntity<Map<String, String>> nullBaseUrl = controller.uploadTestFile(
                file, "k6", "run", 5, null, "ext", null);
        assertThat(nullBaseUrl.getStatusCode().value()).isEqualTo(400);

        when(summarizerService.getByName("ext")).thenReturn(SummarizerModelDto.builder()
                .id(UUID.randomUUID()).name("ext").provider("EXTERNAL").modelId("m").baseUrl("ftp://bad").enabled(true)
                .createdAt(OffsetDateTime.MIN).updatedAt(OffsetDateTime.MIN).build());
        ResponseEntity<Map<String, String>> badBaseUrl = controller.uploadTestFile(
                file, "k6", "run", 5, null, "ext", null);
        assertThat(badBaseUrl.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void upload_rejectsWhenToolExtensionsNull() throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn("x.js");
        when(file.getBytes()).thenReturn("ok".getBytes());
        when(loadTestToolService.getToolByName("K6")).thenReturn(LoadTestToolDto.builder()
                .id(UUID.randomUUID()).name("K6").dockerImage("k6").fileExtensions(null).enabled(true)
                .createdAt(OffsetDateTime.MIN).updatedAt(OffsetDateTime.MIN).build());

        ResponseEntity<Map<String, String>> resp = controller.uploadTestFile(
                file, "k6", "run", 5, null, null, null);
        assertThat(resp.getStatusCode().value()).isEqualTo(500);
    }

    @Test
    void upload_metricsConfigWithRequests_andTrimmedSummarizer() throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn("x.js");
        when(file.getBytes()).thenReturn("ok".getBytes());
        when(loadTestToolService.getToolByName("K6")).thenReturn(LoadTestToolDto.builder()
                .id(UUID.randomUUID()).name("K6").dockerImage("k6").fileExtensions(List.of(".js")).enabled(true)
                .createdAt(OffsetDateTime.MIN).updatedAt(OffsetDateTime.MIN).build());
        when(summarizerService.getByName("route")).thenReturn(SummarizerModelDto.builder()
                .id(UUID.randomUUID()).name("route").provider("OPENAI").modelId("m").enabled(true)
                .createdAt(OffsetDateTime.MIN).updatedAt(OffsetDateTime.MIN).build());
        when(metricsConfigParser.parseMetricsConfigRequests("{\"delaySeconds\":1}"))
                .thenReturn(new com.loadtest.app.dto.TestTaskMessage.MetricsConfig(
                        1,
                        List.of(new com.loadtest.app.dto.TestTaskMessage.MetricsConfig.MetricsRequest(
                                "r1", "GET", "http://m", null, null, null))));
        UUID profileId = UUID.randomUUID();
        when(dockerExecutionProfileService.resolveProfileIdForUpload(isNull())).thenReturn(profileId);
        when(testQueueService.enqueueTest(
                anyString(), anyString(), anyString(), anyString(), any(),
                any(), any(), eq("route"), any())).thenReturn("tid");

        ResponseEntity<Map<String, String>> resp = controller.uploadTestFile(
                file, "k6", "run", 5, "{\"delaySeconds\":1}", " route ", null);
        assertThat(resp.getStatusCode().value()).isEqualTo(202);
    }
}

