package com.loadtest.summarization.service;

import com.loadtest.summarization.persistence.TaskArtifactsRepository;
import com.loadtest.summarization.persistence.TaskMetricsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PromptBuilder {

    private static final String PROMPT_TEMPLATE_RESOURCE =
            "prompts/standard-summarization-prompt-template.txt";

    private static final String PROMPT_TEMPLATE = loadPromptTemplate();

    private static final String EMPTY_DATA_MESSAGE =
            "Нет артефактов и метрик для суммаризации по данной задаче.";

    private static final int MAX_ARTIFACT_TEXT_CHARS = 25_000;
    private static final int MAX_METRICS_JSON_CHARS = 15_000;

    private static final DateTimeFormatter METRICS_TIME_FMT =
            DateTimeFormatter.ISO_LOCAL_DATE_TIME.withZone(ZoneId.systemDefault());

    private final TaskArtifactsRepository taskArtifactsRepository;
    private final TaskMetricsRepository taskMetricsRepository;

    public String buildPrompt(UUID taskId) {
        List<TaskArtifactsRepository.ArtifactContent> artifacts =
                taskArtifactsRepository.findArtifactsByTaskId(taskId);
        List<TaskMetricsRepository.MetricsRow> metrics = taskMetricsRepository.findByTaskId(taskId);
        if (artifacts.isEmpty() && metrics.isEmpty()) {
            return EMPTY_DATA_MESSAGE;
        }
        logPromptBuild(taskId, artifacts, metrics);
        String sourceData = buildSourceDataSection(artifacts, metrics);
        return PROMPT_TEMPLATE + sourceData;
    }

    private static String loadPromptTemplate() {
        InputStream in = PromptBuilder.class.getClassLoader().getResourceAsStream(PROMPT_TEMPLATE_RESOURCE);
        if (in == null) {
            throw new IllegalStateException("Classpath resource not found: " + PROMPT_TEMPLATE_RESOURCE);
        }
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read resource: " + PROMPT_TEMPLATE_RESOURCE, e);
        }
    }

    private void logPromptBuild(
            UUID taskId,
            List<TaskArtifactsRepository.ArtifactContent> artifacts,
            List<TaskMetricsRepository.MetricsRow> metrics) {
        String fileList = joinFileNames(artifacts);
        String metricsList = joinMetricsLabels(metrics);
        log.info("Building prompt for taskId={}: {} file(s), {} metrics row(s). Files: [{}]; metrics: [{}]",
                taskId, artifacts.size(), metrics.size(), fileList, metricsList);
    }

    private String buildSourceDataSection(
            List<TaskArtifactsRepository.ArtifactContent> artifacts,
            List<TaskMetricsRepository.MetricsRow> metrics) {
        StringBuilder sb = new StringBuilder();
        appendSourceIntro(sb, artifacts, metrics);
        appendArtifacts(sb, artifacts);
        appendMetrics(sb, metrics);
        return sb.toString();
    }

    private void appendSourceIntro(
            StringBuilder sb,
            List<TaskArtifactsRepository.ArtifactContent> artifacts,
            List<TaskMetricsRepository.MetricsRow> metrics) {
        sb.append("В блоке ниже перечислено ");
        sb.append(artifacts.size()).append(" файл(ов)");
        if (!metrics.isEmpty()) {
            sb.append(" и ").append(metrics.size()).append(" записей собранных метрик");
        }
        sb.append(". Обязательно используй данные из КАЖДОГО источника для выводов.\n");
        sb.append("По этим данным составь суммаризацию с выводами: интерпретируй метрики (что хорошо, что плохо), ");
        sb.append("укажи проблемы и дай рекомендации. Не ограничивайся перечислением метрик — нужны именно выводы и рекомендации.\n");
        String fileList = joinFileNames(artifacts);
        sb.append("Список файлов: ").append(fileList.isEmpty() ? "—" : fileList).append("\n");
        if (!metrics.isEmpty()) {
            sb.append("Источники метрик: ").append(joinMetricsLabels(metrics)).append("\n");
        }
        sb.append("\n");
    }

    private static void appendArtifacts(
            StringBuilder sb,
            List<TaskArtifactsRepository.ArtifactContent> artifacts) {
        for (TaskArtifactsRepository.ArtifactContent artifact : artifacts) {
            sb.append("--- Файл: ").append(artifact.getFileName()).append(" ---\n");
            String text = truncate(
                    artifact.getTextContent(),
                    MAX_ARTIFACT_TEXT_CHARS,
                    "\n[... обрезано по размеру ...]");
            sb.append(text).append("\n\n");
        }
    }

    private void appendMetrics(StringBuilder sb, List<TaskMetricsRepository.MetricsRow> metrics) {
        if (metrics.isEmpty()) {
            return;
        }
        sb.append("--- Собранные метрики (запрошенные по конфигу) ---\n\n");
        for (TaskMetricsRepository.MetricsRow row : metrics) {
            appendMetricsRow(sb, row);
        }
    }

    private void appendMetricsRow(StringBuilder sb, TaskMetricsRepository.MetricsRow row) {
        sb.append("--- Метрики: ").append(row.getSourceType());
        if (row.getEndpointUrl() != null && !row.getEndpointUrl().isBlank()) {
            sb.append(" | ").append(row.getEndpointUrl());
        }
        if (row.getCollectedAt() != null) {
            sb.append(" | собрано: ").append(METRICS_TIME_FMT.format(row.getCollectedAt()));
        }
        sb.append(" ---\n");
        String json = row.getMetricsDataJson();
        String body = truncate(json, MAX_METRICS_JSON_CHARS, "\n[... обрезано ...]");
        sb.append(!body.isEmpty() ? body : "{}").append("\n\n");
    }

    private static String joinFileNames(List<TaskArtifactsRepository.ArtifactContent> artifacts) {
        return artifacts.stream()
                .map(TaskArtifactsRepository.ArtifactContent::getFileName)
                .collect(Collectors.joining(", "));
    }

    private static String joinMetricsLabels(List<TaskMetricsRepository.MetricsRow> metrics) {
        return metrics.stream()
                .map(PromptBuilder::formatMetricsLabel)
                .collect(Collectors.joining("; "));
    }

    private static String formatMetricsLabel(TaskMetricsRepository.MetricsRow row) {
        String label = row.getSourceType();
        if (row.getEndpointUrl() != null && !row.getEndpointUrl().isBlank()) {
            label += " (" + row.getEndpointUrl() + ")";
        }
        return label;
    }

    private static String truncate(String value, int maxChars, String truncatedSuffix) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars) + truncatedSuffix;
    }
}
