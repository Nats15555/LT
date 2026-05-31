package com.loadtest.summarization.service;

import com.loadtest.summarization.persistence.TaskArtifactsRepository;
import com.loadtest.summarization.persistence.TaskMetricsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PromptBuilder {

    private static final String EMPTY_DATA_MESSAGE =
            "Нет артефактов и метрик для суммаризации по данной задаче.";

    private static final int MAX_ARTIFACT_TEXT_CHARS = 25_000;
    private static final int MAX_METRICS_JSON_CHARS = 15_000;

    private static final String MANDATORY_INSTRUCTION = """
            Задача: отчёт по результатам нагрузочного теста. Используй ТОЛЬКО блок «Исходные данные» ниже (все файлы и все строки метрик). ФОРМАТ ОТВЕТА — СТРОГИЙ: начинай сразу с первой секции «## Краткое содержание»; не пиши преамбул («конечно», «ниже отчёт»). Разрешены РОВНО пять секций с заголовками ## именно в таком порядке и без пропусков: «## Краткое содержание», «## Плюсы», «## Минусы», «## Предложения», «## Итог». Не добавляй другие секции уровня ## (ни «Ключевые метрики», ни «Рекомендации» отдельно — цифры и рекомендации вписывай в Плюсы/Минусы/Предложения/Итог). В «## Плюсы» и «## Минусы» — маркированные списки (- …), каждый пункт с привязкой к факту из логов/метрик (число, процент, имя метрики, файл). В «## Предложения» — нумерованный список (1. 2. 3.) из конкретных шагов (что изменить в коде, конфиге, лимитах, сценарии нагрузки). В «## Итог» — 3–5 предложений: стоит ли выпускать в прод, главный риск, что проверить повторно. Если по блоку нет данных — одна строка: «Нет данных». Запрещено отказываться от отчёта или писать, что вопросы не требуют ответа. Напиши отчёт сейчас.

            """;

    private static final String REPORT_TEMPLATE = """
            Строго соблюдай заголовки и порядок секций (ровно как ниже, на русском, с ## и переносами строк):

            ## Краткое содержание
            [2–4 предложения: цель прогона, общий вывод pass/fail, ключевые цифры если есть в данных]

            ## Плюсы
            [Только маркеры «- ». Что прошло хорошо: стабильность, запас по задержкам, RPS, отсутствие ошибок — с цифрами из артефактов/метрик. Нет плюсов — строка «- Нет данных»]

            ## Минусы
            [Только маркеры «- ». Риски и слабые места: ошибки, рост задержек, провалы RPS, аномалии в JSON метрик — с цифрами/ссылкой на источник. Нет минусов — «- Не выявлено» или «- Нет данных»]

            ## Предложения
            [Только нумерация «1. » «2. » … Конкретные действия: что поменять в тесте, в приложении, в инфраструктуре, какие пороги мониторинга задать]

            ## Итог
            [Связный текст 3–5 предложений: приемлем ли результат для продакшена, главный риск, что повторить в следующем прогоне]

            Не дублируй заголовки секций внутри текста; не выводи сырой JSON целиком — только выжимка для читателя.""";

    private static final String SOURCE_DATA_HEADER = """


            --- Исходные данные ---

            """;

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
        return MANDATORY_INSTRUCTION + REPORT_TEMPLATE + SOURCE_DATA_HEADER + sourceData;
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
