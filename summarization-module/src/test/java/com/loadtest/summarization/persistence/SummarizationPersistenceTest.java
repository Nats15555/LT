package com.loadtest.summarization.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SummarizationPersistenceTest {

    @Test
    void taskHistoryRepository_branches() {
        TestTaskHistoryJpaRepository jpa = mock(TestTaskHistoryJpaRepository.class);
        TaskHistoryRepository repo = new TaskHistoryRepository(jpa);
        UUID id = UUID.randomUUID();

        doReturn(Optional.of(historyWithSummarizer("route"))).when(jpa).findById(id);
        assertThat(repo.getSummarizerNameByTaskId(id)).contains("route");

        doReturn(Optional.of(historyWithSummarizer(null))).when(jpa).findById(id);
        assertThat(repo.getSummarizerNameByTaskId(id)).isEmpty();

        doReturn(Optional.of(historyWithSummarizer("   "))).when(jpa).findById(id);
        assertThat(repo.getSummarizerNameByTaskId(id)).isEmpty();

        doThrow(new RuntimeException("db")).when(jpa).findById(id);
        assertThat(repo.getSummarizerNameByTaskId(id)).isEmpty();

        doReturn(Optional.empty()).when(jpa).findById(id);
        assertThat(repo.getSummarizerNameByTaskId(id)).isEmpty();
    }

    @Test
    void summarizerModelRepository_overrideAndFallbackBranches() {
        SummarizerModelJpaRepository jpa = mock(SummarizerModelJpaRepository.class);
        SummarizerModelRepository repo = new SummarizerModelRepository(jpa, "http://litellm:4000/");
        UUID id = UUID.randomUUID();

        when(jpa.findByNameAndEnabledTrue("r")).thenReturn(Optional.of(modelEntity(id, "r", "http://localhost:4000", "")));
        Optional<SummarizerConfig> cfg = repo.findByName("r");
        assertThat(cfg).isPresent();
        assertThat(cfg.get().getBaseUrl()).isEqualTo("http://litellm:4000");

        when(jpa.findByNameAndEnabledTrue("missing")).thenReturn(Optional.empty());
        assertThat(repo.findByName("missing")).isEmpty();

        SummarizerModelRepository repo127 = new SummarizerModelRepository(jpa, "http://litellm:4000/");
        when(jpa.findByNameAndEnabledTrue("r2")).thenReturn(Optional.of(modelEntity(id, "r2", "http://127.0.0.1:4000", "")));
        assertThat(repo127.findByName("r2")).map(SummarizerConfig::getBaseUrl).contains("http://litellm:4000");

        SummarizerModelRepository repoNoOverride = new SummarizerModelRepository(jpa, "");
        when(jpa.findByNameAndEnabledTrue("r3")).thenReturn(Optional.of(modelEntity(id, "r3", "http://localhost:4000", "")));
        assertThat(repoNoOverride.findByName("r3")).map(SummarizerConfig::getBaseUrl).contains("http://localhost:4000");

        SummarizerModelRepository repoOtherHost = new SummarizerModelRepository(jpa, "http://litellm:4000");
        when(jpa.findByNameAndEnabledTrue("r4")).thenReturn(Optional.of(modelEntity(id, "r4", "http://other-host:8080/v1/", "")));
        assertThat(repoOtherHost.findByName("r4")).map(SummarizerConfig::getBaseUrl).contains("http://other-host:8080/v1/");

        SummarizerModelRepository repoEnvKey = new SummarizerModelRepository(jpa, "");
        when(jpa.findByNameAndEnabledTrue("r5")).thenReturn(Optional.of(modelEntity(id, "r5", "http://x", "UNSET_ENV_XYZ_12345")));
        assertThat(repoEnvKey.findByName("r5")).map(SummarizerConfig::getApiKeyResolved).isEmpty();

        SummarizerModelRepository repoNullBase = new SummarizerModelRepository(jpa, "http://litellm:4000");
        when(jpa.findByNameAndEnabledTrue("r6")).thenReturn(Optional.of(modelEntity(id, "r6", null, "")));
        assertThat(repoNullBase.findByName("r6")).map(SummarizerConfig::getBaseUrl).isEmpty();

        when(jpa.findByNameAndEnabledTrue("any")).thenThrow(new RuntimeException("query-fail"));
        assertThat(repo.findByName("any")).isEmpty();
    }

    @Test
    void summarizerModelRepository_nullApiKeyEnvVarAndNullLitellmOverride() {
        SummarizerModelJpaRepository jpa = mock(SummarizerModelJpaRepository.class);
        SummarizerModelRepository repo = new SummarizerModelRepository(jpa, null);
        UUID id = UUID.randomUUID();

        when(jpa.findByNameAndEnabledTrue("r-null-env"))
                .thenReturn(Optional.of(modelEntity(id, "r-null-env", "http://localhost:4000", null)));

        Optional<SummarizerConfig> cfg = repo.findByName("r-null-env");
        assertThat(cfg).isPresent();
        assertThat(cfg.get().getApiKeyEnvVar()).isNull();
        assertThat(cfg.get().getApiKeyResolved()).isNull();
        assertThat(cfg.get().getBaseUrl()).isEqualTo("http://localhost:4000");
    }

    @Test
    void testSummaryWriter_completedStatus_setsProcessedAt() throws Exception {
        TestSummaryJpaRepository jpa = mock(TestSummaryJpaRepository.class);
        ObjectMapper mapper = mock(ObjectMapper.class);
        TestSummaryWriter writer = new TestSummaryWriter(jpa, mapper);
        UUID id = UUID.randomUUID();

        when(mapper.writeValueAsString(any())).thenReturn("{}");
        writer.saveSummary(id, "AI_SUMMARY", Map.of("x", 1), "COMPLETED", null);

        verify(jpa).save(any(TestSummaryEntity.class));
    }

    @Test
    void testSummaryWriter_serializationFallbackAndInsertFailure() throws Exception {
        TestSummaryJpaRepository jpa = mock(TestSummaryJpaRepository.class);
        ObjectMapper mapper = mock(ObjectMapper.class);
        TestSummaryWriter writer = new TestSummaryWriter(jpa, mapper);
        UUID id = UUID.randomUUID();

        when(mapper.writeValueAsString(any()))
                .thenThrow(new JsonProcessingException("ser") {})
                .thenReturn("{}");
        writer.saveSummary(id, "AI_SUMMARY", Map.of("k", "v"), "PROCESSING", null);
        verify(jpa).save(any(TestSummaryEntity.class));

        when(jpa.save(any(TestSummaryEntity.class))).thenThrow(new RuntimeException("db"));
        assertThatThrownBy(() -> writer.saveSummary(id, "AI_SUMMARY", Map.of(), "FAILED", "x"))
                .isInstanceOf(TestSummaryWriter.TestSummarySaveException.class)
                .hasMessageContaining("Failed to save summary");
    }

    @Test
    void taskArtifactsAndMetricsRepositories_basicBranches() throws Exception {
        TestArtifactJpaRepository artifactJpa = mock(TestArtifactJpaRepository.class);
        TestMetricsJpaRepository metricsJpa = mock(TestMetricsJpaRepository.class);
        TaskArtifactsRepository artifacts = new TaskArtifactsRepository(artifactJpa);
        TaskMetricsRepository metrics = new TaskMetricsRepository(metricsJpa);
        UUID id = UUID.randomUUID();

        when(artifactJpa.findByTaskIdOrderByFileName(id)).thenReturn(List.of(
                artifactEntity("a.txt", "none", "hello".getBytes())));
        List<TaskArtifactsRepository.ArtifactContent> loaded = artifacts.findArtifactsByTaskId(id);
        assertThat(loaded).hasSize(1);
        assertThat(loaded.get(0).getTextContent()).isEqualTo("hello");

        when(artifactJpa.findByTaskIdOrderByFileName(id)).thenReturn(List.of(
                artifactEntity("skip.bin", "none", null)));
        assertThat(artifacts.findArtifactsByTaskId(id)).isEmpty();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzos = new GZIPOutputStream(baos)) {
            gzos.write("gzipped".getBytes(StandardCharsets.UTF_8));
        }
        when(artifactJpa.findByTaskIdOrderByFileName(id)).thenReturn(List.of(
                artifactEntity("a.gz", "gzip", baos.toByteArray())));
        assertThat(artifacts.findArtifactsByTaskId(id).get(0).getTextContent()).isEqualTo("gzipped");

        String decoded = (String) ReflectionTestUtils.invokeMethod(artifacts, "decodeContent", "bad".getBytes(), true);
        assertThat(decoded).contains("binary or unsupported");

        when(metricsJpa.findByTaskIdOrderByCollectedAtAsc(id)).thenReturn(List.of(
                metricsEntity("s", "http://u", "{}", null)));
        assertThat(metrics.findByTaskId(id)).hasSize(1);

        Instant collected = Instant.parse("2024-01-02T12:00:00Z");
        when(metricsJpa.findByTaskIdOrderByCollectedAtAsc(id)).thenReturn(List.of(
                metricsEntity("prom2", "", null, collected)));
        List<TaskMetricsRepository.MetricsRow> withTs = metrics.findByTaskId(id);
        assertThat(withTs).hasSize(1);
        assertThat(withTs.get(0).getCollectedAt()).isEqualTo(collected);
        assertThat(withTs.get(0).getMetricsDataJson()).isNull();

        when(metricsJpa.findByTaskIdOrderByCollectedAtAsc(id)).thenThrow(new RuntimeException("metrics-db"));
        assertThat(metrics.findByTaskId(id)).isEmpty();
    }

    private static TestTaskHistoryEntity historyWithSummarizer(String summarizerName) {
        return TestTaskHistoryEntity.builder()
                .id(UUID.randomUUID())
                .finalStatus("COMPLETED")
                .createdAt(OffsetDateTime.now())
                .movedAt(OffsetDateTime.now())
                .testTool("k6")
                .testFileName("f.js")
                .testFileContentBase64("YQ==")
                .command("run")
                .summarizerName(summarizerName)
                .build();
    }

    private static SummarizerModelEntity modelEntity(UUID id, String name, String baseUrl, String apiKeyEnvVar) {
        OffsetDateTime now = OffsetDateTime.now();
        return SummarizerModelEntity.builder()
                .id(id)
                .name(name)
                .provider("OPENAI")
                .baseUrl(baseUrl)
                .modelId("gpt")
                .apiKeyEnvVar(apiKeyEnvVar)
                .enabled(true)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private static TestArtifactEntity artifactEntity(String fileName, String encoding, byte[] content) {
        return TestArtifactEntity.builder()
                .id(UUID.randomUUID())
                .taskId(UUID.randomUUID())
                .fileName(fileName)
                .contentEncoding(encoding)
                .fileContent(content)
                .createdAt(OffsetDateTime.now())
                .build();
    }

    private static TestMetricsEntity metricsEntity(String sourceType, String endpointUrl, String metricsData, Instant collectedAt) {
        return TestMetricsEntity.builder()
                .id(UUID.randomUUID())
                .taskId(UUID.randomUUID())
                .sourceType(sourceType)
                .endpointUrl(endpointUrl)
                .metricsData(metricsData)
                .collectedAt(collectedAt != null ? collectedAt.atOffset(OffsetDateTime.now().getOffset()) : null)
                .build();
    }
}
