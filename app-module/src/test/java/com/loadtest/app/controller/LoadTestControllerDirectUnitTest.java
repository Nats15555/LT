package com.loadtest.app.controller;

import com.loadtest.app.dto.LoadTestToolDto;
import com.loadtest.app.dto.SummarizerModelDto;
import com.loadtest.app.service.CustomSummarizationPromptStore;
import com.loadtest.app.service.DockerExecutionProfileService;
import com.loadtest.app.service.LoadTestUploadService;
import com.loadtest.app.service.LoadTestToolService;
import com.loadtest.app.service.SummarizerService;
import com.loadtest.app.service.TestQueueService;
import com.loadtest.app.util.ApiMessages;
import com.loadtest.app.util.MetricsConfigParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
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
import static com.loadtest.app.testsupport.MultipartFileTestSupport.stubBytes;
import static com.loadtest.app.testsupport.MultipartFileTestSupport.stubIoFailure;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LoadTestControllerDirectUnitTest {

    private static final String TEST_PROFILE_ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";

    private TestQueueService testQueueService;
    private MetricsConfigParser metricsConfigParser;
    private LoadTestToolService loadTestToolService;
    private SummarizerService summarizerService;
    private DockerExecutionProfileService dockerExecutionProfileService;
    private LoadTestUploadService loadTestUploadService;
    private LoadTestController controller;

    @BeforeEach
    void setUp() {
        testQueueService = mock(TestQueueService.class);
        metricsConfigParser = mock(MetricsConfigParser.class);
        loadTestToolService = mock(LoadTestToolService.class);
        summarizerService = mock(SummarizerService.class);
        dockerExecutionProfileService = mock(DockerExecutionProfileService.class);
        CustomSummarizationPromptStore customSummarizationPromptStore = new CustomSummarizationPromptStore();
        loadTestUploadService = new LoadTestUploadService(
                testQueueService,
                metricsConfigParser,
                loadTestToolService,
                summarizerService,
                dockerExecutionProfileService,
                customSummarizationPromptStore);
        ReflectionTestUtils.setField(loadTestUploadService, "maxScenarioFileSizeBytes", 10_485_760L);
        controller = new LoadTestController(loadTestUploadService);
    }

    @Test
    void upload_catchesIOExceptionBranch() {
        MultipartFile bad = mock(MultipartFile.class);
        when(bad.getOriginalFilename()).thenReturn("x.js");
        when(loadTestToolService.getToolByName("K6")).thenReturn(new LoadTestToolDto(
                UUID.randomUUID(), "K6", "k6", List.of(".js"), true, OffsetDateTime.MIN, OffsetDateTime.MIN));
        when(dockerExecutionProfileService.resolveProfileIdForUpload(eq(TEST_PROFILE_ID))).thenReturn(UUID.randomUUID());
        stubIoFailure(bad, new IOException("io"));

        ResponseEntity<Map<String, Object>> resp = controller.uploadTestFile(
                bad, "k6", "run", 5, null, null, null, TEST_PROFILE_ID);
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody()).containsEntry("status", "error");
    }

    @Test
    void upload_catchesGenericExceptionBranch() {
        MultipartFile good = mock(MultipartFile.class);
        when(good.getOriginalFilename()).thenReturn("x.js");
        stubBytes(good, "ok".getBytes());
        when(loadTestToolService.getToolByName("K6")).thenReturn(new LoadTestToolDto(
                UUID.randomUUID(), "K6", "k6", List.of(".js"), true, OffsetDateTime.MIN, OffsetDateTime.MIN));
        when(dockerExecutionProfileService.resolveProfileIdForUpload(eq(TEST_PROFILE_ID))).thenReturn(UUID.randomUUID());
        when(testQueueService.enqueueTest(anyString(), anyString(), anyString(), anyString(), any(),
                any(), any(), any(), any())).thenThrow(new RuntimeException("boom"));

        ResponseEntity<Map<String, Object>> resp = controller.uploadTestFile(
                good, "k6", "run", 5, null, null, null, TEST_PROFILE_ID);
        assertThat(resp.getStatusCode().value()).isEqualTo(500);
    }

    @Test
    void upload_rejectsNullCommand_andNullDuration() {
        MultipartFile file = mock(MultipartFile.class);
        ResponseEntity<Map<String, Object>> noCmd = controller.uploadTestFile(
                file, "k6", null, 5, null, null, null, TEST_PROFILE_ID);
        assertThat(noCmd.getStatusCode().value()).isEqualTo(400);

        ResponseEntity<Map<String, Object>> noDur = controller.uploadTestFile(
                file, "k6", "run", null, null, null, null, TEST_PROFILE_ID);
        assertThat(noDur.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void upload_rejectsMissingDockerProfile() {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn("x.js");
        when(file.isEmpty()).thenReturn(false);
        stubBytes(file, "ok".getBytes());
        when(loadTestToolService.getToolByName("K6")).thenReturn(new LoadTestToolDto(
                UUID.randomUUID(), "K6", "k6", List.of(".js"), true, OffsetDateTime.MIN, OffsetDateTime.MIN));
        doThrow(new IllegalArgumentException(ApiMessages.Upload.DOCKER_EXECUTION_PROFILE_ID_REQUIRED))
                .when(dockerExecutionProfileService).resolveProfileIdForUpload(isNull());
        doThrow(new IllegalArgumentException(ApiMessages.Upload.DOCKER_EXECUTION_PROFILE_ID_REQUIRED))
                .when(dockerExecutionProfileService).resolveProfileIdForUpload("  ");

        assertThat(controller.uploadTestFile(file, "k6", "run", 5, null, null, null, null)
                .getStatusCode().value()).isEqualTo(400);
        assertThat(controller.uploadTestFile(file, "k6", "run", 5, null, null, null, "  ")
                .getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void upload_rejectsExternalSummarizerWithNullAndInvalidBaseUrl() {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn("x.js");
        when(loadTestToolService.getToolByName("K6")).thenReturn(new LoadTestToolDto(
                UUID.randomUUID(), "K6", "k6", List.of(".js"), true, OffsetDateTime.MIN, OffsetDateTime.MIN));

        when(summarizerService.getByName("ext")).thenReturn(new SummarizerModelDto(
                UUID.randomUUID(), "ext", "EXTERNAL", null, "m", null, true, OffsetDateTime.MIN, OffsetDateTime.MIN));
        ResponseEntity<Map<String, Object>> nullBaseUrl = controller.uploadTestFile(
                file, "k6", "run", 5, null, "ext", null, TEST_PROFILE_ID);
        assertThat(nullBaseUrl.getStatusCode().value()).isEqualTo(400);

        when(summarizerService.getByName("ext")).thenReturn(new SummarizerModelDto(
                UUID.randomUUID(), "ext", "EXTERNAL", "ftp://bad", "m", null, true, OffsetDateTime.MIN, OffsetDateTime.MIN));
        ResponseEntity<Map<String, Object>> badBaseUrl = controller.uploadTestFile(
                file, "k6", "run", 5, null, "ext", null, TEST_PROFILE_ID);
        assertThat(badBaseUrl.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void upload_rejectsWhenToolExtensionsNull() {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn("x.js");
        stubBytes(file, "ok".getBytes());
        when(loadTestToolService.getToolByName("K6")).thenReturn(new LoadTestToolDto(
                UUID.randomUUID(), "K6", "k6", null, true, OffsetDateTime.MIN, OffsetDateTime.MIN));

        ResponseEntity<Map<String, Object>> resp = controller.uploadTestFile(
                file, "k6", "run", 5, null, null, null, TEST_PROFILE_ID);
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void upload_metricsConfigWithRequests_andTrimmedSummarizer() {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn("x.js");
        stubBytes(file, "ok".getBytes());
        when(loadTestToolService.getToolByName("K6")).thenReturn(new LoadTestToolDto(
                UUID.randomUUID(), "K6", "k6", List.of(".js"), true, OffsetDateTime.MIN, OffsetDateTime.MIN));
        when(summarizerService.getByName("route")).thenReturn(new SummarizerModelDto(
                UUID.randomUUID(), "route", "OPENAI", null, "m", null, true, OffsetDateTime.MIN, OffsetDateTime.MIN));
        when(metricsConfigParser.parseMetricsConfigRequests("{\"delaySeconds\":1}"))
                .thenReturn(new com.loadtest.app.dto.TestTaskMessage.MetricsConfig(
                        1,
                        List.of(new com.loadtest.app.dto.TestTaskMessage.MetricsConfig.MetricsRequest(
                                "r1", "GET", "http://m", null, null, null))));
        UUID profileId = UUID.randomUUID();
        when(dockerExecutionProfileService.resolveProfileIdForUpload(eq(TEST_PROFILE_ID))).thenReturn(profileId);
        when(testQueueService.enqueueTest(
                anyString(), anyString(), anyString(), anyString(), any(),
                any(), any(), eq("route"), any())).thenReturn("tid");

        ResponseEntity<Map<String, Object>> resp = controller.uploadTestFile(
                file, "k6", "run", 5, "{\"delaySeconds\":1}", " route ", null, TEST_PROFILE_ID);
        assertThat(resp.getStatusCode().value()).isEqualTo(202);
    }
}

