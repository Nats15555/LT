package com.loadtest.summarization.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SummarizationPersistenceTest {

    @Test
    @SuppressWarnings("unchecked")
    void taskHistoryRepository_branches() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TaskHistoryRepository repo = new TaskHistoryRepository(jdbc);
        UUID id = UUID.randomUUID();

        when(jdbc.query(anyString(), any(ResultSetExtractor.class), any())).thenAnswer(inv -> {
            ResultSetExtractor<Optional<String>> ex = inv.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.next()).thenReturn(true);
            when(rs.getString("summarizer_name")).thenReturn("route");
            return ex.extractData(rs);
        });
        assertThat(repo.getSummarizerNameByTaskId(id)).contains("route");

        when(jdbc.query(anyString(), any(ResultSetExtractor.class), any())).thenAnswer(inv -> {
            ResultSetExtractor<Optional<String>> ex = inv.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.next()).thenReturn(true);
            when(rs.getString("summarizer_name")).thenReturn(null);
            return ex.extractData(rs);
        });
        assertThat(repo.getSummarizerNameByTaskId(id)).isEmpty();

        when(jdbc.query(anyString(), any(ResultSetExtractor.class), any())).thenAnswer(inv -> {
            ResultSetExtractor<Optional<String>> ex = inv.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.next()).thenReturn(true);
            when(rs.getString("summarizer_name")).thenReturn("   ");
            return ex.extractData(rs);
        });
        assertThat(repo.getSummarizerNameByTaskId(id)).isEmpty();

        when(jdbc.query(anyString(), any(ResultSetExtractor.class), any())).thenThrow(new RuntimeException("db"));
        assertThat(repo.getSummarizerNameByTaskId(id)).isEmpty();

        when(jdbc.query(anyString(), any(ResultSetExtractor.class), any())).thenAnswer(inv -> {
            ResultSetExtractor<Optional<String>> ex = inv.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.next()).thenReturn(false);
            return ex.extractData(rs);
        });
        assertThat(repo.getSummarizerNameByTaskId(id)).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void summarizerModelRepository_overrideAndFallbackBranches() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SummarizerModelRepository repo = new SummarizerModelRepository(jdbc, "http://litellm:4000/");
        UUID id = UUID.randomUUID();

        when(jdbc.query(anyString(), any(ResultSetExtractor.class), any())).thenAnswer(inv -> {
            ResultSetExtractor<Optional<SummarizerConfig>> ex = inv.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.next()).thenReturn(true);
            when(rs.getString("id")).thenReturn(id.toString());
            when(rs.getString("name")).thenReturn("r");
            when(rs.getString("provider")).thenReturn("OPENAI");
            when(rs.getString("base_url")).thenReturn("http://localhost:4000");
            when(rs.getString("model_id")).thenReturn("gpt");
            when(rs.getString("api_key_env_var")).thenReturn("");
            return ex.extractData(rs);
        });
        Optional<SummarizerConfig> cfg = repo.findByName("r");
        assertThat(cfg).isPresent();
        assertThat(cfg.get().getBaseUrl()).isEqualTo("http://litellm:4000");

        when(jdbc.query(anyString(), any(ResultSetExtractor.class), any())).thenAnswer(inv -> {
            ResultSetExtractor<Optional<SummarizerConfig>> ex = inv.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.next()).thenReturn(false);
            return ex.extractData(rs);
        });
        assertThat(repo.findByName("missing")).isEmpty();

        SummarizerModelRepository repo127 = new SummarizerModelRepository(jdbc, "http://litellm:4000/");
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), any())).thenAnswer(inv -> {
            ResultSetExtractor<Optional<SummarizerConfig>> ex = inv.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.next()).thenReturn(true);
            when(rs.getString("id")).thenReturn(id.toString());
            when(rs.getString("name")).thenReturn("r2");
            when(rs.getString("provider")).thenReturn("OPENAI");
            when(rs.getString("base_url")).thenReturn("http://127.0.0.1:4000");
            when(rs.getString("model_id")).thenReturn("gpt");
            when(rs.getString("api_key_env_var")).thenReturn("");
            return ex.extractData(rs);
        });
        assertThat(repo127.findByName("r2")).map(SummarizerConfig::getBaseUrl).contains("http://litellm:4000");

        SummarizerModelRepository repoNoOverride = new SummarizerModelRepository(jdbc, "");
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), any())).thenAnswer(inv -> {
            ResultSetExtractor<Optional<SummarizerConfig>> ex = inv.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.next()).thenReturn(true);
            when(rs.getString("id")).thenReturn(id.toString());
            when(rs.getString("name")).thenReturn("r3");
            when(rs.getString("provider")).thenReturn("OPENAI");
            when(rs.getString("base_url")).thenReturn("http://localhost:4000");
            when(rs.getString("model_id")).thenReturn("gpt");
            when(rs.getString("api_key_env_var")).thenReturn("");
            return ex.extractData(rs);
        });
        assertThat(repoNoOverride.findByName("r3")).map(SummarizerConfig::getBaseUrl).contains("http://localhost:4000");

        SummarizerModelRepository repoOtherHost = new SummarizerModelRepository(jdbc, "http://litellm:4000");
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), any())).thenAnswer(inv -> {
            ResultSetExtractor<Optional<SummarizerConfig>> ex = inv.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.next()).thenReturn(true);
            when(rs.getString("id")).thenReturn(id.toString());
            when(rs.getString("name")).thenReturn("r4");
            when(rs.getString("provider")).thenReturn("OPENAI");
            when(rs.getString("base_url")).thenReturn("http://other-host:8080/v1/");
            when(rs.getString("model_id")).thenReturn("gpt");
            when(rs.getString("api_key_env_var")).thenReturn("");
            return ex.extractData(rs);
        });
        assertThat(repoOtherHost.findByName("r4")).map(SummarizerConfig::getBaseUrl).contains("http://other-host:8080/v1/");

        SummarizerModelRepository repoEnvKey = new SummarizerModelRepository(jdbc, "");
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), any())).thenAnswer(inv -> {
            ResultSetExtractor<Optional<SummarizerConfig>> ex = inv.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.next()).thenReturn(true);
            when(rs.getString("id")).thenReturn(id.toString());
            when(rs.getString("name")).thenReturn("r5");
            when(rs.getString("provider")).thenReturn("OPENAI");
            when(rs.getString("base_url")).thenReturn("http://x");
            when(rs.getString("model_id")).thenReturn("m");
            when(rs.getString("api_key_env_var")).thenReturn("UNSET_ENV_XYZ_12345");
            return ex.extractData(rs);
        });
        assertThat(repoEnvKey.findByName("r5")).map(SummarizerConfig::getApiKeyResolved).isEmpty();

        SummarizerModelRepository repoNullBase = new SummarizerModelRepository(jdbc, "http://litellm:4000");
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), any())).thenAnswer(inv -> {
            ResultSetExtractor<Optional<SummarizerConfig>> ex = inv.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.next()).thenReturn(true);
            when(rs.getString("id")).thenReturn(id.toString());
            when(rs.getString("name")).thenReturn("r6");
            when(rs.getString("provider")).thenReturn("OPENAI");
            when(rs.getString("base_url")).thenReturn(null);
            when(rs.getString("model_id")).thenReturn("m");
            when(rs.getString("api_key_env_var")).thenReturn("");
            return ex.extractData(rs);
        });
        assertThat(repoNullBase.findByName("r6")).map(SummarizerConfig::getBaseUrl).isEmpty();

        when(jdbc.query(anyString(), any(ResultSetExtractor.class), any())).thenThrow(new RuntimeException("query-fail"));
        assertThat(repo.findByName("any")).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void summarizerModelRepository_nullApiKeyEnvVarAndNullLitellmOverride() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SummarizerModelRepository repo = new SummarizerModelRepository(jdbc, null);
        UUID id = UUID.randomUUID();

        when(jdbc.query(anyString(), any(ResultSetExtractor.class), any())).thenAnswer(inv -> {
            ResultSetExtractor<Optional<SummarizerConfig>> ex = inv.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.next()).thenReturn(true);
            when(rs.getString("id")).thenReturn(id.toString());
            when(rs.getString("name")).thenReturn("r-null-env");
            when(rs.getString("provider")).thenReturn("OPENAI");
            when(rs.getString("base_url")).thenReturn("http://localhost:4000");
            when(rs.getString("model_id")).thenReturn("gpt");
            when(rs.getString("api_key_env_var")).thenReturn(null);
            return ex.extractData(rs);
        });

        Optional<SummarizerConfig> cfg = repo.findByName("r-null-env");
        assertThat(cfg).isPresent();
        assertThat(cfg.get().getApiKeyEnvVar()).isNull();
        assertThat(cfg.get().getApiKeyResolved()).isNull();
        assertThat(cfg.get().getBaseUrl()).isEqualTo("http://localhost:4000");
    }

    @Test
    void testSummaryWriter_completedStatus_setsProcessedAt() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ObjectMapper mapper = mock(ObjectMapper.class);
        TestSummaryWriter writer = new TestSummaryWriter(jdbc, mapper);
        UUID id = UUID.randomUUID();

        when(mapper.writeValueAsString(any())).thenReturn("{}");
        writer.saveSummary(id, "AI_SUMMARY", Map.of("x", 1), "COMPLETED", null);

        verify(jdbc).update(
                anyString(),
                any(),
                eq(id),
                eq("AI_SUMMARY"),
                any(),
                eq("COMPLETED"),
                isNull(),
                any(),
                notNull());
    }

    @Test
    void testSummaryWriter_serializationFallbackAndInsertFailure() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ObjectMapper mapper = mock(ObjectMapper.class);
        TestSummaryWriter writer = new TestSummaryWriter(jdbc, mapper);
        UUID id = UUID.randomUUID();

        when(mapper.writeValueAsString(any()))
                .thenThrow(new JsonProcessingException("ser") {})
                .thenReturn("{}");
        writer.saveSummary(id, "AI_SUMMARY", Map.of("k", "v"), "PROCESSING", null);
        verify(jdbc).update(anyString(), any(), any(), any(), any(), any(), any(), any(), any());

        when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("db"));
        assertThatThrownBy(() -> writer.saveSummary(id, "AI_SUMMARY", Map.of(), "FAILED", "x"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to save summary");
    }

    @Test
    @SuppressWarnings("unchecked")
    void taskArtifactsAndMetricsRepositories_basicBranches() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TaskArtifactsRepository artifacts = new TaskArtifactsRepository(jdbc);
        TaskMetricsRepository metrics = new TaskMetricsRepository(jdbc);
        UUID id = UUID.randomUUID();

        doAnswer(inv -> {
            org.springframework.jdbc.core.RowCallbackHandler cb = inv.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getString("file_name")).thenReturn("a.txt");
            when(rs.getString("content_encoding")).thenReturn("none");
            when(rs.getBytes("file_content")).thenReturn("hello".getBytes());
            cb.processRow(rs);
            return null;
        }).when(jdbc).query(anyString(), any(org.springframework.jdbc.core.RowCallbackHandler.class), any());
        List<TaskArtifactsRepository.ArtifactContent> loaded = artifacts.findArtifactsByTaskId(id);
        assertThat(loaded).hasSize(1);
        assertThat(loaded.get(0).getTextContent()).isEqualTo("hello");

        doAnswer(inv -> {
            org.springframework.jdbc.core.RowCallbackHandler cb = inv.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getString("file_name")).thenReturn("skip.bin");
            when(rs.getString("content_encoding")).thenReturn("none");
            when(rs.getBytes("file_content")).thenReturn(null);
            cb.processRow(rs);
            return null;
        }).when(jdbc).query(anyString(), any(org.springframework.jdbc.core.RowCallbackHandler.class), any());
        assertThat(artifacts.findArtifactsByTaskId(id)).isEmpty();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzos = new GZIPOutputStream(baos)) {
            gzos.write("gzipped".getBytes(StandardCharsets.UTF_8));
        }
        byte[] gz = baos.toByteArray();
        doAnswer(inv -> {
            org.springframework.jdbc.core.RowCallbackHandler cb = inv.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getString("file_name")).thenReturn("a.gz");
            when(rs.getString("content_encoding")).thenReturn("gzip");
            when(rs.getBytes("file_content")).thenReturn(gz);
            cb.processRow(rs);
            return null;
        }).when(jdbc).query(anyString(), any(org.springframework.jdbc.core.RowCallbackHandler.class), any());
        assertThat(artifacts.findArtifactsByTaskId(id).get(0).getTextContent()).isEqualTo("gzipped");

        String decoded = (String) ReflectionTestUtils.invokeMethod(artifacts, "decodeContent", "bad".getBytes(), true);
        assertThat(decoded).contains("binary or unsupported");

        doAnswer(inv -> {
            org.springframework.jdbc.core.RowCallbackHandler cb = inv.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getString("source_type")).thenReturn("s");
            when(rs.getString("endpoint_url")).thenReturn("http://u");
            when(rs.getString("metrics_data")).thenReturn("{}");
            when(rs.getTimestamp("collected_at")).thenReturn(null);
            cb.processRow(rs);
            return null;
        }).when(jdbc).query(anyString(), any(org.springframework.jdbc.core.RowCallbackHandler.class), any());
        assertThat(metrics.findByTaskId(id)).hasSize(1);

        Instant collected = Instant.parse("2024-01-02T12:00:00Z");
        doAnswer(inv -> {
            org.springframework.jdbc.core.RowCallbackHandler cb = inv.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getString("source_type")).thenReturn("prom2");
            when(rs.getString("endpoint_url")).thenReturn("");
            when(rs.getString("metrics_data")).thenReturn(null);
            when(rs.getTimestamp("collected_at")).thenReturn(Timestamp.from(collected));
            cb.processRow(rs);
            return null;
        }).when(jdbc).query(anyString(), any(org.springframework.jdbc.core.RowCallbackHandler.class), any());
        List<TaskMetricsRepository.MetricsRow> withTs = metrics.findByTaskId(id);
        assertThat(withTs).hasSize(1);
        assertThat(withTs.get(0).getCollectedAt()).isEqualTo(collected);
        assertThat(withTs.get(0).getMetricsDataJson()).isNull();

        doAnswer(inv -> {
            throw new RuntimeException("metrics-db");
        }).when(jdbc).query(anyString(), any(org.springframework.jdbc.core.RowCallbackHandler.class), any());
        assertThat(metrics.findByTaskId(id)).isEmpty();
    }
}
