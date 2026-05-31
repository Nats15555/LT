package com.loadtest.app.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadtest.app.persistence.DockerExecutionProfileEntity;
import com.loadtest.app.persistence.DockerExecutionProfileRepository;
import com.loadtest.app.persistence.TestMetricsEntity;
import com.loadtest.app.persistence.TestSummaryEntity;
import com.loadtest.app.persistence.TestTaskHistoryEntity;
import com.loadtest.app.persistence.TestTaskRepository;
import com.loadtest.app.persistence.TestTaskHistoryRepository;
import com.loadtest.app.persistence.TestArtifactRepository;
import com.loadtest.app.persistence.TestMetricsRepository;
import com.loadtest.app.persistence.TestSummaryRepository;
import com.loadtest.app.persistence.SummarizerModelRepository;
import com.loadtest.app.service.ExternalLlmDispatchService;
import com.loadtest.app.service.ExternalSummarizationCallbackService;
import com.loadtest.app.service.KafkaOutboxService;
import com.loadtest.app.service.QueuePauseService;
import com.loadtest.app.service.TestQueueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TasksControllerPrivateMethodsTest {

    private DockerExecutionProfileRepository dockerExecutionProfileRepository;
    private TasksController controller;

    @BeforeEach
    void setUp() {
        dockerExecutionProfileRepository = mock(DockerExecutionProfileRepository.class);
        controller = new TasksController(
                dockerExecutionProfileRepository,
                mock(TestTaskRepository.class),
                mock(TestTaskHistoryRepository.class),
                mock(TestArtifactRepository.class),
                mock(TestMetricsRepository.class),
                mock(TestSummaryRepository.class),
                mock(SummarizerModelRepository.class),
                mock(ExternalSummarizationCallbackService.class),
                mock(ExternalLlmDispatchService.class),
                new com.loadtest.app.service.CustomSummarizationPromptStore(),
                mock(KafkaOutboxService.class),
                mock(TestQueueService.class),
                mock(QueuePauseService.class),
                new ObjectMapper());
    }

    @Test
    void contentTypeFromFileName_coversAllBranches() {
        assertThat((String) ReflectionTestUtils.invokeMethod(controller, "contentTypeFromFileName", new Object[]{null}))
                .isEqualTo("application/octet-stream");
        assertThat((String) ReflectionTestUtils.invokeMethod(controller, "contentTypeFromFileName", "a.html"))
                .isEqualTo("text/html; charset=utf-8");
        assertThat((String) ReflectionTestUtils.invokeMethod(controller, "contentTypeFromFileName", "a.csv"))
                .isEqualTo("text/csv; charset=utf-8");
        assertThat((String) ReflectionTestUtils.invokeMethod(controller, "contentTypeFromFileName", "a.json"))
                .isEqualTo("application/json; charset=utf-8");
        assertThat((String) ReflectionTestUtils.invokeMethod(controller, "contentTypeFromFileName", "a.xml"))
                .isEqualTo("application/xml; charset=utf-8");
        assertThat((String) ReflectionTestUtils.invokeMethod(controller, "contentTypeFromFileName", "a.bin"))
                .isEqualTo("application/octet-stream");
    }

    @Test
    void resolveProfileName_andHistoryDto_branches() {
        UUID pid = UUID.randomUUID();
        when(dockerExecutionProfileRepository.findById(pid)).thenReturn(Optional.of(
                DockerExecutionProfileEntity.builder().id(pid).name("prof").build()));
        assertThat((String) ReflectionTestUtils.invokeMethod(controller, "resolveProfileName", pid)).isEqualTo("prof");
        assertThat((String) ReflectionTestUtils.invokeMethod(controller, "resolveProfileName", new Object[]{null})).isNull();

        TestTaskHistoryEntity ok = TestTaskHistoryEntity.builder()
                .id(UUID.randomUUID())
                .finalStatus("OK")
                .testTool("K6")
                .testFileName("f.js")
                .testFileContentBase64(Base64.getEncoder().encodeToString("x".getBytes(StandardCharsets.UTF_8)))
                .command("run")
                .createdAt(OffsetDateTime.now())
                .metricsConfig("{\"m\":1}")
                .build();
        var dto1 = ReflectionTestUtils.invokeMethod(controller, "toHistoryDtoWithContent", ok);
        assertThat(dto1).isNotNull();

        TestTaskHistoryEntity bad = TestTaskHistoryEntity.builder()
                .id(UUID.randomUUID())
                .finalStatus("OK")
                .testTool("K6")
                .testFileName("f.js")
                .testFileContentBase64("###")
                .command("run")
                .createdAt(OffsetDateTime.now())
                .metricsConfig(" ")
                .build();
        var dto2 = ReflectionTestUtils.invokeMethod(controller, "toHistoryDtoWithContent", bad);
        assertThat(dto2).isNotNull();

        TestTaskHistoryEntity empty = TestTaskHistoryEntity.builder()
                .id(UUID.randomUUID())
                .finalStatus("OK")
                .testTool("K6")
                .testFileName("f.js")
                .testFileContentBase64("")
                .command("run")
                .createdAt(OffsetDateTime.now())
                .build();
        var dto3 = ReflectionTestUtils.invokeMethod(controller, "toHistoryDtoWithContent", empty);
        assertThat(dto3).isNotNull();
    }

    @Test
    void metricsAndSummaryDto_fallbacks() {
        TestMetricsEntity m1 = TestMetricsEntity.builder()
                .id(UUID.randomUUID())
                .sourceType("P")
                .endpointUrl("u")
                .metricsData("{\"v\":1}")
                .collectedAt(OffsetDateTime.now())
                .build();
        TestMetricsEntity m2 = TestMetricsEntity.builder()
                .id(UUID.randomUUID())
                .sourceType("P")
                .endpointUrl("u")
                .metricsData("{bad")
                .build();
        TestMetricsEntity m3 = TestMetricsEntity.builder()
                .id(UUID.randomUUID())
                .sourceType("P")
                .endpointUrl("u")
                .metricsData(" ")
                .build();
        assertThat((Object) ReflectionTestUtils.invokeMethod(controller, "toMetricsDto", m1)).isNotNull();
        assertThat((Object) ReflectionTestUtils.invokeMethod(controller, "toMetricsDto", m2)).isNotNull();
        assertThat((Object) ReflectionTestUtils.invokeMethod(controller, "toMetricsDto", m3)).isNotNull();

        TestSummaryEntity s1 = TestSummaryEntity.builder()
                .id(UUID.randomUUID())
                .taskId(UUID.randomUUID())
                .summaryType("AI")
                .summaryData("\"{\\\"inner\\\":1}\"")
                .build();
        TestSummaryEntity s2 = TestSummaryEntity.builder()
                .id(UUID.randomUUID())
                .taskId(UUID.randomUUID())
                .summaryType("AI")
                .summaryData("{bad")
                .build();
        TestSummaryEntity s3 = TestSummaryEntity.builder()
                .id(UUID.randomUUID())
                .taskId(UUID.randomUUID())
                .summaryType("AI")
                .summaryData(" ")
                .build();
        TestSummaryEntity s4 = TestSummaryEntity.builder()
                .id(UUID.randomUUID())
                .taskId(UUID.randomUUID())
                .summaryType("AI")
                .summaryData("\"   \"")
                .build();
        assertThat((Object) ReflectionTestUtils.invokeMethod(controller, "toSummaryDto", s1)).isNotNull();
        assertThat((Object) ReflectionTestUtils.invokeMethod(controller, "toSummaryDto", s2)).isNotNull();
        assertThat((Object) ReflectionTestUtils.invokeMethod(controller, "toSummaryDto", s3)).isNotNull();
        assertThat((Object) ReflectionTestUtils.invokeMethod(controller, "toSummaryDto", s4)).isNotNull();
    }
}

