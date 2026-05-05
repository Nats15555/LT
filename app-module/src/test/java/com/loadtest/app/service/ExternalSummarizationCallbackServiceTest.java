package com.loadtest.app.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadtest.app.persistence.SummarizerModelEntity;
import com.loadtest.app.persistence.SummarizerModelRepository;
import com.loadtest.app.persistence.TestArtifactEntity;
import com.loadtest.app.persistence.TestArtifactRepository;
import com.loadtest.app.persistence.TestMetricsEntity;
import com.loadtest.app.persistence.TestMetricsRepository;
import com.loadtest.app.persistence.TestSummaryEntity;
import com.loadtest.app.persistence.TestSummaryRepository;
import com.loadtest.app.persistence.TestTaskHistoryEntity;
import com.loadtest.app.persistence.TestTaskHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExternalSummarizationCallbackServiceTest {

    @Mock
    private TestTaskHistoryRepository historyRepository;
    @Mock
    private SummarizerModelRepository summarizerModelRepository;
    @Mock
    private TestArtifactRepository artifactRepository;
    @Mock
    private TestMetricsRepository metricsRepository;
    @Mock
    private TestSummaryRepository summaryRepository;
    @Mock
    private JdbcTemplate jdbcTemplate;

    private ObjectMapper objectMapper;
    private ExternalSummarizationCallbackService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new ExternalSummarizationCallbackService(
                historyRepository,
                summarizerModelRepository,
                artifactRepository,
                metricsRepository,
                summaryRepository,
                jdbcTemplate,
                objectMapper);
        ReflectionTestUtils.setField(service, "windowMinutes", 5);
    }

    @Test
    void registerPendingWindow_writesJdbc() {
        UUID taskId = UUID.randomUUID();
        service.registerPendingWindow(taskId, "ext");
        verify(jdbcTemplate, atLeastOnce()).update(contains("DELETE FROM test_summary"), eq(taskId), eq(ExternalSummarizationCallbackService.PROCESSING_STATUS_AWAITING));
        verify(jdbcTemplate, atLeastOnce()).update(contains("INSERT INTO test_summary"), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void registerPendingWindow_jsonFailureFallsBackToEmptyObject() throws Exception {
        ObjectMapper badOm = mock(ObjectMapper.class);
        when(badOm.writeValueAsString(any())).thenThrow(new JsonProcessingException("x") {});
        ExternalSummarizationCallbackService s2 = new ExternalSummarizationCallbackService(
                historyRepository, summarizerModelRepository, artifactRepository, metricsRepository, summaryRepository, jdbcTemplate, badOm);
        ReflectionTestUtils.setField(s2, "windowMinutes", 1);
        s2.registerPendingWindow(UUID.randomUUID(), "ext");
        verify(jdbcTemplate).update(contains("INSERT INTO test_summary"), any(), any(), any(), eq("{}"), any(), any(), any());
    }

    @Test
    void buildPackage_happyPath_andMetricsJsonFallback() {
        UUID taskId = UUID.randomUUID();
        OffsetDateTime t = OffsetDateTime.now(ZoneOffset.UTC);
        when(historyRepository.findById(taskId)).thenReturn(Optional.of(
                TestTaskHistoryEntity.builder()
                        .id(taskId)
                        .finalStatus("OK")
                        .createdAt(t)
                        .movedAt(t)
                        .testTool("K6")
                        .testFileName("f.js")
                        .testFileContentBase64("QQ==")
                        .command("run")
                        .summarizerName("route")
                        .startedAt(t)
                        .finishedAt(t)
                        .errorMessage("err")
                        .build()));
        when(summarizerModelRepository.findByName("route")).thenReturn(Optional.of(
                SummarizerModelEntity.builder()
                        .id(UUID.randomUUID())
                        .name("route")
                        .provider("EXTERNAL")
                        .modelId("m")
                        .enabled(true)
                        .createdAt(t)
                        .updatedAt(t)
                        .build()));
        String deadline = OffsetDateTime.now(ZoneOffset.UTC).plusHours(2).toString();
        when(summaryRepository.findFirstByTaskIdAndProcessingStatusOrderByCreatedAtDesc(taskId, ExternalSummarizationCallbackService.PROCESSING_STATUS_AWAITING))
                .thenReturn(Optional.of(TestSummaryEntity.builder()
                        .id(UUID.randomUUID())
                        .taskId(taskId)
                        .summaryType("AI_SUMMARY")
                        .summaryData("{\"deadlineAt\":\"" + deadline + "\",\"instructionsRu\":\"do\"}")
                        .processingStatus(ExternalSummarizationCallbackService.PROCESSING_STATUS_AWAITING)
                        .createdAt(t)
                        .build()));
        when(summaryRepository.findByTaskIdAndProcessingStatusOrderByCreatedAtDesc(taskId, ExternalSummarizationCallbackService.PROCESSING_STATUS_AWAITING))
                .thenReturn(List.of());
        when(metricsRepository.findByTaskIdOrderByCollectedAtAsc(taskId)).thenReturn(List.of(
                TestMetricsEntity.builder()
                        .id(UUID.randomUUID())
                        .taskId(taskId)
                        .sourceType("P")
                        .endpointUrl("u")
                        .metricsData("not-json")
                        .collectedAt(t)
                        .build()));
        when(artifactRepository.findByTaskIdOrderByFileName(taskId)).thenReturn(List.of(
                TestArtifactEntity.builder()
                        .id(UUID.randomUUID())
                        .taskId(taskId)
                        .fileName("a.bin")
                        .fileContent(new byte[] {1, 2})
                        .contentEncoding("identity")
                        .createdAt(t)
                        .build()));

        Map<String, Object> pkg = service.buildPackage(taskId);
        assertThat(pkg).containsKeys("taskId", "metrics", "artifacts", "summarizationPromptRu");
        assertThat((List<?>) pkg.get("metrics")).hasSize(1);
        assertThat((List<?>) pkg.get("artifacts")).hasSize(1);
    }

    @Test
    void buildPackage_errors() {
        UUID id = UUID.randomUUID();
        when(historyRepository.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.buildPackage(id))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(HttpStatus.NOT_FOUND.value()));

        OffsetDateTime t = OffsetDateTime.now(ZoneOffset.UTC);
        when(historyRepository.findById(id)).thenReturn(Optional.of(
                TestTaskHistoryEntity.builder()
                        .id(id).finalStatus("OK").createdAt(t).movedAt(t).testTool("k6").testFileName("f.js")
                        .testFileContentBase64("QQ==").command("c").summarizerName(null).build()));
        assertThatThrownBy(() -> service.buildPackage(id))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(HttpStatus.BAD_REQUEST.value()));

        when(historyRepository.findById(id)).thenReturn(Optional.of(
                TestTaskHistoryEntity.builder()
                        .id(id).finalStatus("OK").createdAt(t).movedAt(t).testTool("k6").testFileName("f.js")
                        .testFileContentBase64("QQ==").command("c").summarizerName("r").build()));
        when(summarizerModelRepository.findByName("r")).thenReturn(Optional.of(
                SummarizerModelEntity.builder().id(UUID.randomUUID()).name("r").provider("OPENAI").modelId("m").enabled(true).createdAt(t).updatedAt(t).build()));
        assertThatThrownBy(() -> service.buildPackage(id))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(HttpStatus.BAD_REQUEST.value()));
    }

    @Test
    void buildPackage_deadlineExpired_marksFailedAndGone() {
        UUID taskId = UUID.randomUUID();
        OffsetDateTime t = OffsetDateTime.now(ZoneOffset.UTC);
        when(historyRepository.findById(taskId)).thenReturn(Optional.of(
                TestTaskHistoryEntity.builder()
                        .id(taskId).finalStatus("OK").createdAt(t).movedAt(t).testTool("k6").testFileName("f.js")
                        .testFileContentBase64("QQ==").command("c").summarizerName("r").build()));
        when(summarizerModelRepository.findByName("r")).thenReturn(Optional.of(
                SummarizerModelEntity.builder().id(UUID.randomUUID()).name("r").provider("EXTERNAL").modelId("m").enabled(true).createdAt(t).updatedAt(t).build()));
        when(summaryRepository.findByTaskIdAndProcessingStatusOrderByCreatedAtDesc(taskId, ExternalSummarizationCallbackService.PROCESSING_STATUS_AWAITING))
                .thenReturn(List.of());
        when(summaryRepository.findFirstByTaskIdAndProcessingStatusOrderByCreatedAtDesc(taskId, ExternalSummarizationCallbackService.PROCESSING_STATUS_AWAITING))
                .thenReturn(Optional.of(TestSummaryEntity.builder()
                        .id(UUID.randomUUID())
                        .taskId(taskId)
                        .summaryType("AI_SUMMARY")
                        .summaryData("{\"deadlineAt\":\"" + OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1) + "\"}")
                        .processingStatus(ExternalSummarizationCallbackService.PROCESSING_STATUS_AWAITING)
                        .createdAt(t)
                        .build()));
        assertThatThrownBy(() -> service.buildPackage(taskId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(HttpStatus.GONE.value()));
        verify(summaryRepository, atLeastOnce()).save(any(TestSummaryEntity.class));
    }

    @Test
    void submitExternalSummary_validatesAndCompletes() {
        UUID taskId = UUID.randomUUID();
        OffsetDateTime t = OffsetDateTime.now(ZoneOffset.UTC);
        String deadline = OffsetDateTime.now(ZoneOffset.UTC).plusHours(1).toString();
        assertThatThrownBy(() -> service.submitExternalSummary(taskId, "  "))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(HttpStatus.BAD_REQUEST.value()));

        when(historyRepository.findById(taskId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.submitExternalSummary(taskId, "ok"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(HttpStatus.NOT_FOUND.value()));

        when(historyRepository.findById(taskId)).thenReturn(Optional.of(
                TestTaskHistoryEntity.builder()
                        .id(taskId).finalStatus("OK").createdAt(t).movedAt(t).testTool("k6").testFileName("f.js")
                        .testFileContentBase64("QQ==").command("c").summarizerName(null).build()));
        assertThatThrownBy(() -> service.submitExternalSummary(taskId, "ok"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(HttpStatus.BAD_REQUEST.value()));

        when(historyRepository.findById(taskId)).thenReturn(Optional.of(
                TestTaskHistoryEntity.builder()
                        .id(taskId).finalStatus("OK").createdAt(t).movedAt(t).testTool("k6").testFileName("f.js")
                        .testFileContentBase64("QQ==").command("c").summarizerName("r").build()));
        when(summarizerModelRepository.findByName("r")).thenReturn(Optional.of(
                SummarizerModelEntity.builder().id(UUID.randomUUID()).name("r").provider("OPENAI").modelId("m").enabled(true).createdAt(t).updatedAt(t).build()));
        assertThatThrownBy(() -> service.submitExternalSummary(taskId, "ok"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(HttpStatus.BAD_REQUEST.value()));

        when(summarizerModelRepository.findByName("r")).thenReturn(Optional.of(
                SummarizerModelEntity.builder().id(UUID.randomUUID()).name("r").provider("EXTERNAL").modelId("m").enabled(true).createdAt(t).updatedAt(t).build()));
        when(summaryRepository.findByTaskIdAndProcessingStatusOrderByCreatedAtDesc(taskId, ExternalSummarizationCallbackService.PROCESSING_STATUS_AWAITING))
                .thenReturn(List.of());
        when(summaryRepository.findFirstByTaskIdAndProcessingStatusOrderByCreatedAtDesc(taskId, ExternalSummarizationCallbackService.PROCESSING_STATUS_AWAITING))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.submitExternalSummary(taskId, "ok"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(HttpStatus.NOT_FOUND.value()));

        when(summaryRepository.findFirstByTaskIdAndProcessingStatusOrderByCreatedAtDesc(taskId, ExternalSummarizationCallbackService.PROCESSING_STATUS_AWAITING))
                .thenReturn(Optional.of(TestSummaryEntity.builder()
                        .id(UUID.randomUUID())
                        .taskId(taskId)
                        .summaryType("AI_SUMMARY")
                        .summaryData("{\"deadlineAt\":\"" + deadline + "\"}")
                        .processingStatus(ExternalSummarizationCallbackService.PROCESSING_STATUS_AWAITING)
                        .createdAt(t)
                        .build()));
        service.submitExternalSummary(taskId, "report text");
        verify(summaryRepository).deleteByTaskIdAndProcessingStatus(taskId, ExternalSummarizationCallbackService.PROCESSING_STATUS_AWAITING);
        verify(summaryRepository).save(any(TestSummaryEntity.class));
    }

    @Test
    void submitExternalSummary_expiredWindow() {
        UUID taskId = UUID.randomUUID();
        OffsetDateTime t = OffsetDateTime.now(ZoneOffset.UTC);
        when(historyRepository.findById(taskId)).thenReturn(Optional.of(
                TestTaskHistoryEntity.builder()
                        .id(taskId).finalStatus("OK").createdAt(t).movedAt(t).testTool("k6").testFileName("f.js")
                        .testFileContentBase64("QQ==").command("c").summarizerName("r").build()));
        when(summarizerModelRepository.findByName("r")).thenReturn(Optional.of(
                SummarizerModelEntity.builder().id(UUID.randomUUID()).name("r").provider("EXTERNAL").modelId("m").enabled(true).createdAt(t).updatedAt(t).build()));
        when(summaryRepository.findByTaskIdAndProcessingStatusOrderByCreatedAtDesc(taskId, ExternalSummarizationCallbackService.PROCESSING_STATUS_AWAITING))
                .thenReturn(List.of());
        when(summaryRepository.findFirstByTaskIdAndProcessingStatusOrderByCreatedAtDesc(taskId, ExternalSummarizationCallbackService.PROCESSING_STATUS_AWAITING))
                .thenReturn(Optional.of(TestSummaryEntity.builder()
                        .id(UUID.randomUUID())
                        .taskId(taskId)
                        .summaryType("AI_SUMMARY")
                        .summaryData("{\"deadlineAt\":\"" + OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(2) + "\"}")
                        .processingStatus(ExternalSummarizationCallbackService.PROCESSING_STATUS_AWAITING)
                        .createdAt(t)
                        .build()));
        assertThatThrownBy(() -> service.submitExternalSummary(taskId, "ok"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(HttpStatus.GONE.value()));
        verify(summaryRepository, atLeastOnce()).save(any(TestSummaryEntity.class));
    }

    @Test
    void failPendingWindow_marksOrNoop() {
        UUID taskId = UUID.randomUUID();
        when(summaryRepository.findByTaskIdAndProcessingStatusOrderByCreatedAtDesc(taskId, ExternalSummarizationCallbackService.PROCESSING_STATUS_AWAITING))
                .thenReturn(List.of());
        service.failPendingWindow(taskId, "reason");
        verify(summaryRepository, atLeastOnce()).findByTaskIdAndProcessingStatusOrderByCreatedAtDesc(taskId, ExternalSummarizationCallbackService.PROCESSING_STATUS_AWAITING);

        OffsetDateTime t = OffsetDateTime.now(ZoneOffset.UTC);
        TestSummaryEntity row = TestSummaryEntity.builder()
                .id(UUID.randomUUID())
                .taskId(taskId)
                .summaryType("AI_SUMMARY")
                .summaryData("{}")
                .processingStatus(ExternalSummarizationCallbackService.PROCESSING_STATUS_AWAITING)
                .createdAt(t)
                .build();
        when(summaryRepository.findByTaskIdAndProcessingStatusOrderByCreatedAtDesc(taskId, ExternalSummarizationCallbackService.PROCESSING_STATUS_AWAITING))
                .thenReturn(List.of(row));
        service.failPendingWindow(taskId, " ");
        verify(summaryRepository).save(row);
        assertThat(row.getProcessingStatus()).isEqualTo("FAILED");
    }

    @Test
    void helperParsers_coverInvalidAndBlankBranches() {
        String blankInstructions = ReflectionTestUtils.invokeMethod(service, "readInstructionsRu", "   ");
        assertThat(blankInstructions).isEmpty();
        String badInstructions = ReflectionTestUtils.invokeMethod(service, "readInstructionsRu", "{bad");
        assertThat(badInstructions).isEmpty();

        OffsetDateTime d1 = ReflectionTestUtils.invokeMethod(service, "readDeadline", "   ");
        OffsetDateTime d2 = ReflectionTestUtils.invokeMethod(service, "readDeadline", "{\"x\":1}");
        OffsetDateTime d3 = ReflectionTestUtils.invokeMethod(service, "readDeadline", "{\"deadlineAt\":\"oops\"}");
        assertThat(d1).isNull();
        assertThat(d2).isNull();
        assertThat(d3).isNull();
    }

    @Test
    void submitExternalSummary_expiresStalePendingRowsBeforeCurrentWindow() {
        UUID taskId = UUID.randomUUID();
        OffsetDateTime t = OffsetDateTime.now(ZoneOffset.UTC);
        when(historyRepository.findById(taskId)).thenReturn(Optional.of(
                TestTaskHistoryEntity.builder()
                        .id(taskId).finalStatus("OK").createdAt(t).movedAt(t).testTool("k6").testFileName("f.js")
                        .testFileContentBase64("QQ==").command("c").summarizerName("r").build()));
        when(summarizerModelRepository.findByName("r")).thenReturn(Optional.of(
                SummarizerModelEntity.builder().id(UUID.randomUUID()).name("r").provider("EXTERNAL").modelId("m").enabled(true).createdAt(t).updatedAt(t).build()));

        TestSummaryEntity stale = TestSummaryEntity.builder()
                .id(UUID.randomUUID())
                .taskId(taskId)
                .summaryType("AI_SUMMARY")
                .summaryData("{\"deadlineAt\":\"" + OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(10) + "\"}")
                .processingStatus(ExternalSummarizationCallbackService.PROCESSING_STATUS_AWAITING)
                .createdAt(t.minusMinutes(11))
                .build();
        TestSummaryEntity active = TestSummaryEntity.builder()
                .id(UUID.randomUUID())
                .taskId(taskId)
                .summaryType("AI_SUMMARY")
                .summaryData("{\"deadlineAt\":\"" + OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10) + "\"}")
                .processingStatus(ExternalSummarizationCallbackService.PROCESSING_STATUS_AWAITING)
                .createdAt(t)
                .build();

        when(summaryRepository.findByTaskIdAndProcessingStatusOrderByCreatedAtDesc(taskId, ExternalSummarizationCallbackService.PROCESSING_STATUS_AWAITING))
                .thenReturn(List.of(stale, active));
        when(summaryRepository.findFirstByTaskIdAndProcessingStatusOrderByCreatedAtDesc(taskId, ExternalSummarizationCallbackService.PROCESSING_STATUS_AWAITING))
                .thenReturn(Optional.of(active));

        service.submitExternalSummary(taskId, "ok");

        verify(summaryRepository, atLeastOnce()).save(stale);
        verify(summaryRepository, atLeastOnce()).save(any(TestSummaryEntity.class));
        assertThat(stale.getProcessingStatus()).isEqualTo("FAILED");
    }

    @Test
    void submitExternalSummary_jsonSerializationFailure_returns500() throws Exception {
        ObjectMapper badOm = mock(ObjectMapper.class);
        when(badOm.writeValueAsString(any())).thenThrow(new JsonProcessingException("ser") {});
        ExternalSummarizationCallbackService s2 = new ExternalSummarizationCallbackService(
                historyRepository, summarizerModelRepository, artifactRepository, metricsRepository, summaryRepository, jdbcTemplate, badOm);

        UUID taskId = UUID.randomUUID();
        OffsetDateTime t = OffsetDateTime.now(ZoneOffset.UTC);
        when(historyRepository.findById(taskId)).thenReturn(Optional.of(
                TestTaskHistoryEntity.builder()
                        .id(taskId).finalStatus("OK").createdAt(t).movedAt(t).testTool("k6").testFileName("f.js")
                        .testFileContentBase64("QQ==").command("c").summarizerName("r").build()));
        when(summarizerModelRepository.findByName("r")).thenReturn(Optional.of(
                SummarizerModelEntity.builder().id(UUID.randomUUID()).name("r").provider("EXTERNAL").modelId("m").enabled(true).createdAt(t).updatedAt(t).build()));
        when(summaryRepository.findByTaskIdAndProcessingStatusOrderByCreatedAtDesc(taskId, ExternalSummarizationCallbackService.PROCESSING_STATUS_AWAITING))
                .thenReturn(List.of());
        when(summaryRepository.findFirstByTaskIdAndProcessingStatusOrderByCreatedAtDesc(taskId, ExternalSummarizationCallbackService.PROCESSING_STATUS_AWAITING))
                .thenReturn(Optional.of(TestSummaryEntity.builder()
                        .id(UUID.randomUUID())
                        .taskId(taskId)
                        .summaryType("AI_SUMMARY")
                        .summaryData("{\"deadlineAt\":\"" + OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5) + "\"}")
                        .processingStatus(ExternalSummarizationCallbackService.PROCESSING_STATUS_AWAITING)
                        .createdAt(t)
                        .build()));

        assertThatThrownBy(() -> s2.submitExternalSummary(taskId, "ok"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value()));
    }

    @Test
    void buildPackage_withNullHistoryFieldsAndBlankPendingData() {
        UUID taskId = UUID.randomUUID();
        OffsetDateTime t = OffsetDateTime.now(ZoneOffset.UTC);
        when(historyRepository.findById(taskId)).thenReturn(Optional.of(
                TestTaskHistoryEntity.builder()
                        .id(taskId)
                        .finalStatus(" ")
                        .createdAt(t)
                        .movedAt(t)
                        .testTool(null)
                        .testFileName(null)
                        .testFileContentBase64("QQ==")
                        .command("c")
                        .summarizerName("r")
                        .startedAt(null)
                        .finishedAt(null)
                        .errorMessage(" ")
                        .build()));
        when(summarizerModelRepository.findByName("r")).thenReturn(Optional.of(
                SummarizerModelEntity.builder().id(UUID.randomUUID()).name("r").provider("EXTERNAL").modelId("m").enabled(true).createdAt(t).updatedAt(t).build()));
        when(summaryRepository.findByTaskIdAndProcessingStatusOrderByCreatedAtDesc(taskId, ExternalSummarizationCallbackService.PROCESSING_STATUS_AWAITING))
                .thenReturn(List.of());
        when(summaryRepository.findFirstByTaskIdAndProcessingStatusOrderByCreatedAtDesc(taskId, ExternalSummarizationCallbackService.PROCESSING_STATUS_AWAITING))
                .thenReturn(Optional.of(TestSummaryEntity.builder()
                        .id(UUID.randomUUID())
                        .taskId(taskId)
                        .summaryType("AI_SUMMARY")
                        .summaryData(" ")
                        .processingStatus(ExternalSummarizationCallbackService.PROCESSING_STATUS_AWAITING)
                        .createdAt(t)
                        .build()));
        when(metricsRepository.findByTaskIdOrderByCollectedAtAsc(taskId)).thenReturn(List.of(
                TestMetricsEntity.builder()
                        .id(UUID.randomUUID())
                        .taskId(taskId)
                        .sourceType("P")
                        .endpointUrl("u")
                        .metricsData("{\"ok\":1}")
                        .collectedAt(null)
                        .build()));
        when(artifactRepository.findByTaskIdOrderByFileName(taskId)).thenReturn(List.of());

        Map<String, Object> pkg = service.buildPackage(taskId);
        assertThat(pkg.get("deadlineAt")).isNull();
        assertThat(pkg.get("summarizationPromptRu")).isNotNull();
        assertThat((List<?>) pkg.get("metrics")).hasSize(1);
    }

    @Test
    void nullToDash_helperBranches() {
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "nullToDash", new Object[]{null})).isEqualTo("—");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "nullToDash", "   ")).isEqualTo("—");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "nullToDash", "ok")).isEqualTo("ok");
    }

    @Test
    void failPendingWindow_nonBlankMessage_keepsProvidedReason() {
        UUID taskId = UUID.randomUUID();
        TestSummaryEntity row = TestSummaryEntity.builder()
                .id(UUID.randomUUID())
                .taskId(taskId)
                .summaryType("AI_SUMMARY")
                .summaryData("{}")
                .processingStatus(ExternalSummarizationCallbackService.PROCESSING_STATUS_AWAITING)
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();
        when(summaryRepository.findByTaskIdAndProcessingStatusOrderByCreatedAtDesc(taskId, ExternalSummarizationCallbackService.PROCESSING_STATUS_AWAITING))
                .thenReturn(List.of(row));
        service.failPendingWindow(taskId, "custom");
        assertThat(row.getErrorMessage()).isEqualTo("custom");
    }

    @Test
    void buildPackage_metricsDataNull_andCollectedAtPresent() {
        UUID taskId = UUID.randomUUID();
        OffsetDateTime t = OffsetDateTime.now(ZoneOffset.UTC);
        when(historyRepository.findById(taskId)).thenReturn(Optional.of(
                TestTaskHistoryEntity.builder()
                        .id(taskId).finalStatus("OK").createdAt(t).movedAt(t).testTool("k6").testFileName("f.js")
                        .testFileContentBase64("QQ==").command("c").summarizerName("r").build()));
        when(summarizerModelRepository.findByName("r")).thenReturn(Optional.of(
                SummarizerModelEntity.builder().id(UUID.randomUUID()).name("r").provider("EXTERNAL").modelId("m").enabled(true).createdAt(t).updatedAt(t).build()));
        when(summaryRepository.findByTaskIdAndProcessingStatusOrderByCreatedAtDesc(taskId, ExternalSummarizationCallbackService.PROCESSING_STATUS_AWAITING))
                .thenReturn(List.of());
        when(summaryRepository.findFirstByTaskIdAndProcessingStatusOrderByCreatedAtDesc(taskId, ExternalSummarizationCallbackService.PROCESSING_STATUS_AWAITING))
                .thenReturn(Optional.of(TestSummaryEntity.builder()
                        .id(UUID.randomUUID())
                        .taskId(taskId)
                        .summaryType("AI_SUMMARY")
                        .summaryData("{\"deadlineAt\":\"" + OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5) + "\"}")
                        .processingStatus(ExternalSummarizationCallbackService.PROCESSING_STATUS_AWAITING)
                        .createdAt(t)
                        .build()));
        when(metricsRepository.findByTaskIdOrderByCollectedAtAsc(taskId)).thenReturn(List.of(
                TestMetricsEntity.builder()
                        .id(UUID.randomUUID())
                        .taskId(taskId)
                        .sourceType("P")
                        .endpointUrl("u")
                        .metricsData(null)
                        .collectedAt(t)
                        .build()));
        when(artifactRepository.findByTaskIdOrderByFileName(taskId)).thenReturn(List.of());

        Map<String, Object> pkg = service.buildPackage(taskId);
        assertThat((List<?>) pkg.get("metrics")).hasSize(1);
    }
}
