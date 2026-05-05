package com.loadtest.summarization.service;

import com.loadtest.summarization.persistence.TaskArtifactsRepository;
import com.loadtest.summarization.persistence.TaskMetricsRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PromptBuilderTest {

    @Test
    void buildPrompt_whenNoArtifactsAndMetrics_returnsFallback() {
        TaskArtifactsRepository artifactsRepo = mock(TaskArtifactsRepository.class);
        TaskMetricsRepository metricsRepo = mock(TaskMetricsRepository.class);
        UUID id = UUID.randomUUID();
        when(artifactsRepo.findArtifactsByTaskId(id)).thenReturn(List.of());
        when(metricsRepo.findByTaskId(id)).thenReturn(List.of());

        PromptBuilder builder = new PromptBuilder(artifactsRepo, metricsRepo);
        assertThat(builder.buildPrompt(id)).contains("Нет артефактов и метрик");
    }

    @Test
    void buildPrompt_includesFilesMetricsAndTruncatesLargeSections() {
        TaskArtifactsRepository artifactsRepo = mock(TaskArtifactsRepository.class);
        TaskMetricsRepository metricsRepo = mock(TaskMetricsRepository.class);
        UUID id = UUID.randomUUID();

        String largeText = "a".repeat(26000);
        String largeJson = "b".repeat(16000);
        when(artifactsRepo.findArtifactsByTaskId(id)).thenReturn(List.of(
                new TaskArtifactsRepository.ArtifactContent("a.log", largeText)
        ));
        when(metricsRepo.findByTaskId(id)).thenReturn(List.of(
                new TaskMetricsRepository.MetricsRow("prom", "http://prom:9090/q", largeJson, Instant.now()),
                new TaskMetricsRepository.MetricsRow("no-url", "", "{}", null)
        ));

        PromptBuilder builder = new PromptBuilder(artifactsRepo, metricsRepo);
        String prompt = builder.buildPrompt(id);

        assertThat(prompt).contains("## Краткое содержание");
        assertThat(prompt).contains("Список файлов: a.log");
        assertThat(prompt).contains("Источники метрик: prom (http://prom:9090/q); no-url");
        assertThat(prompt).contains("[... обрезано по размеру ...]");
        assertThat(prompt).contains("[... обрезано ...]");
    }

    @Test
    void buildPrompt_onlyMetrics_noArtifactSectionBody() {
        TaskArtifactsRepository artifactsRepo = mock(TaskArtifactsRepository.class);
        TaskMetricsRepository metricsRepo = mock(TaskMetricsRepository.class);
        UUID id = UUID.randomUUID();
        when(artifactsRepo.findArtifactsByTaskId(id)).thenReturn(List.of());
        when(metricsRepo.findByTaskId(id)).thenReturn(List.of(
                new TaskMetricsRepository.MetricsRow("s", "", "{}", Instant.now())
        ));

        String prompt = new PromptBuilder(artifactsRepo, metricsRepo).buildPrompt(id);
        assertThat(prompt).contains("Список файлов: —");
        assertThat(prompt).contains("Источники метрик:");
        assertThat(prompt).doesNotContain("--- Файл:");
    }

    @Test
    void buildPrompt_onlyArtifacts_skipsMetricsBlock() {
        TaskArtifactsRepository artifactsRepo = mock(TaskArtifactsRepository.class);
        TaskMetricsRepository metricsRepo = mock(TaskMetricsRepository.class);
        UUID id = UUID.randomUUID();
        when(artifactsRepo.findArtifactsByTaskId(id)).thenReturn(List.of(
                new TaskArtifactsRepository.ArtifactContent("f.txt", null)
        ));
        when(metricsRepo.findByTaskId(id)).thenReturn(List.of());

        String prompt = new PromptBuilder(artifactsRepo, metricsRepo).buildPrompt(id);
        assertThat(prompt).contains("--- Файл: f.txt ---");
        assertThat(prompt).doesNotContain("--- Собранные метрики");
    }

    @Test
    void buildPrompt_shortArtifactText_metricRowsWithoutUrlOrJson() {
        TaskArtifactsRepository artifactsRepo = mock(TaskArtifactsRepository.class);
        TaskMetricsRepository metricsRepo = mock(TaskMetricsRepository.class);
        UUID id = UUID.randomUUID();

        String shortBody = "x".repeat(1000);
        when(artifactsRepo.findArtifactsByTaskId(id)).thenReturn(List.of(
                new TaskArtifactsRepository.ArtifactContent("short.log", shortBody)
        ));
        when(metricsRepo.findByTaskId(id)).thenReturn(List.of(
                new TaskMetricsRepository.MetricsRow("no-endpoint", null, null, Instant.now()),
                new TaskMetricsRepository.MetricsRow("blank-url", "   ", "{\"k\":1}", null)
        ));

        String prompt = new PromptBuilder(artifactsRepo, metricsRepo).buildPrompt(id);

        assertThat(shortBody.length()).isLessThanOrEqualTo(25_000);
        assertThat(prompt).contains("--- Файл: short.log ---");
        assertThat(prompt).contains(shortBody);
        assertThat(prompt).doesNotContain("[... обрезано по размеру ...]");

        assertThat(prompt).contains("Источники метрик: no-endpoint; blank-url");
        assertThat(prompt).contains("--- Метрики: no-endpoint | собрано:");
        assertThat(prompt).contains("--- Метрики: blank-url ---");
        assertThat(prompt).doesNotContain("blank-url | ");

        assertThat(prompt).contains("---\n{}\n\n");
        assertThat(prompt).contains("{\"k\":1}");
    }
}
