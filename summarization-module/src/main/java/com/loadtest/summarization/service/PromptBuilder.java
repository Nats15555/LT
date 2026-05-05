package com.loadtest.summarization.service;

import com.loadtest.summarization.persistence.TaskArtifactsRepository;
import com.loadtest.summarization.persistence.TaskMetricsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PromptBuilder {

    private static final String MANDATORY_INSTRUCTION =
            "Задача: отчёт по результатам нагрузочного теста. Используй ТОЛЬКО блок «Исходные данные» ниже (все файлы и все строки метрик). "
                    + "ФОРМАТ ОТВЕТА — СТРОГИЙ: начинай сразу с первой секции «## Краткое содержание»; не пиши преамбул («конечно», «ниже отчёт»). "
                    + "Разрешены РОВНО пять секций с заголовками ## именно в таком порядке и без пропусков: "
                    + "«## Краткое содержание», «## Плюсы», «## Минусы», «## Предложения», «## Итог». "
                    + "Не добавляй другие секции уровня ## (ни «Ключевые метрики», ни «Рекомендации» отдельно — цифры и рекомендации вписывай в Плюсы/Минусы/Предложения/Итог). "
                    + "В «## Плюсы» и «## Минусы» — маркированные списки (- …), каждый пункт с привязкой к факту из логов/метрик (число, процент, имя метрики, файл). "
                    + "В «## Предложения» — нумерованный список (1. 2. 3.) из конкретных шагов (что изменить в коде, конфиге, лимитах, сценарии нагрузки). "
                    + "В «## Итог» — 3–5 предложений: стоит ли выпускать в прод, главный риск, что проверить повторно. "
                    + "Если по блоку нет данных — одна строка: «Нет данных». "
                    + "Запрещено отказываться от отчёта или писать, что вопросы не требуют ответа. "
                    + "Напиши отчёт сейчас.\n\n";

    private static final String REPORT_TEMPLATE =
            "Строго соблюдай заголовки и порядок секций (ровно как ниже, на русском, с ## и переносами строк):\n\n"
                    + "## Краткое содержание\n"
                    + "[2–4 предложения: цель прогона, общий вывод pass/fail, ключевые цифры если есть в данных]\n\n"
                    + "## Плюсы\n"
                    + "[Только маркеры «- ». Что прошло хорошо: стабильность, запас по задержкам, RPS, отсутствие ошибок — с цифрами из артефактов/метрик. Нет плюсов — строка «- Нет данных»]\n\n"
                    + "## Минусы\n"
                    + "[Только маркеры «- ». Риски и слабые места: ошибки, рост задержек, провалы RPS, аномалии в JSON метрик — с цифрами/ссылкой на источник. Нет минусов — «- Не выявлено» или «- Нет данных»]\n\n"
                    + "## Предложения\n"
                    + "[Только нумерация «1. » «2. » … Конкретные действия: что поменять в тесте, в приложении, в инфраструктуре, какие пороги мониторинга задать]\n\n"
                    + "## Итог\n"
                    + "[Связный текст 3–5 предложений: приемлем ли результат для продакшена, главный риск, что повторить в следующем прогоне]\n\n"
                    + "Не дублируй заголовки секций внутри текста; не выводи сырой JSON целиком — только выжимка для читателя.";

    private static final DateTimeFormatter METRICS_TIME_FMT =
            DateTimeFormatter.ISO_LOCAL_DATE_TIME.withZone(ZoneId.systemDefault());

    private final TaskArtifactsRepository taskArtifactsRepository;
    private final TaskMetricsRepository taskMetricsRepository;

    public String buildPrompt(java.util.UUID taskId) {
        List<TaskArtifactsRepository.ArtifactContent> artifacts = taskArtifactsRepository.findArtifactsByTaskId(taskId);
        List<TaskMetricsRepository.MetricsRow> metrics = taskMetricsRepository.findByTaskId(taskId);
        if (artifacts.isEmpty() && metrics.isEmpty()) {
            return "Нет артефактов и метрик для суммаризации по данной задаче.";
        }
        String fileList = artifacts.stream()
                .map(TaskArtifactsRepository.ArtifactContent::getFileName)
                .collect(Collectors.joining(", "));
        String metricsList = metrics.stream()
                .map(m -> m.getSourceType() + (m.getEndpointUrl() != null && !m.getEndpointUrl().isBlank() ? " (" + m.getEndpointUrl() + ")" : ""))
                .collect(Collectors.joining("; "));

        log.info("Building prompt for taskId={}: {} file(s), {} metrics row(s). Files: [{}]; metrics: [{}]",
                taskId, artifacts.size(), metrics.size(), fileList, metricsList);

        StringBuilder sb = new StringBuilder();
        sb.append("В блоке ниже перечислено ");
        sb.append(artifacts.size()).append(" файл(ов)");
        if (!metrics.isEmpty()) {
            sb.append(" и ").append(metrics.size()).append(" записей собранных метрик");
        }
        sb.append(". Обязательно используй данные из КАЖДОГО источника для выводов.\n");
        sb.append("По этим данным составь суммаризацию с выводами: интерпретируй метрики (что хорошо, что плохо), укажи проблемы и дай рекомендации. Не ограничивайся перечислением метрик — нужны именно выводы и рекомендации.\n");
        sb.append("Список файлов: ").append(fileList.isEmpty() ? "—" : fileList).append("\n");
        if (!metrics.isEmpty()) {
            sb.append("Источники метрик: ").append(metricsList).append("\n");
        }
        sb.append("\n");

        for (TaskArtifactsRepository.ArtifactContent a : artifacts) {
            sb.append("--- Файл: ").append(a.getFileName()).append(" ---\n");
            String text = a.getTextContent();
            if (text != null && text.length() > 25_000) {
                text = text.substring(0, 25_000) + "\n[... обрезано по размеру ...]";
            }
            sb.append(text != null ? text : "").append("\n\n");
        }

        if (!metrics.isEmpty()) {
            sb.append("--- Собранные метрики (запрошенные по конфигу) ---\n\n");
            for (TaskMetricsRepository.MetricsRow m : metrics) {
                sb.append("--- Метрики: ").append(m.getSourceType());
                if (m.getEndpointUrl() != null && !m.getEndpointUrl().isBlank()) {
                    sb.append(" | ").append(m.getEndpointUrl());
                }
                if (m.getCollectedAt() != null) {
                    sb.append(" | собрано: ").append(METRICS_TIME_FMT.format(m.getCollectedAt()));
                }
                sb.append(" ---\n");
                String json = m.getMetricsDataJson();
                if (json != null && json.length() > 15_000) {
                    json = json.substring(0, 15_000) + "\n[... обрезано ...]";
                }
                sb.append(json != null ? json : "{}").append("\n\n");
            }
        }

        return MANDATORY_INSTRUCTION
                + REPORT_TEMPLATE
                + "\n\n--- Исходные данные ---\n\n"
                + sb;
    }
}
