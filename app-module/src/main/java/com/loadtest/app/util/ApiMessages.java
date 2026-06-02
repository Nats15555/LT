package com.loadtest.app.util;

import java.util.UUID;

public final class ApiMessages {

    private static final String QUOTED_IDENTIFIER_NOT_FOUND_SUFFIX = "' not found";

    private ApiMessages() {
    }

    public static final class PromptTemplate {
        public static final String DESCRIPTION =
                "Стандартный промпт формируется автоматически после сбора артефактов и метрик прогона.";

        private PromptTemplate() {
        }
    }

    public static final class Upload {
        public static final String TASK_ADDED = "Test task added to queue";
        public static final String COMMAND_REQUIRED = "Command is required";
        public static final String EXPECTED_DURATION_REQUIRED =
                "expectedDurationSeconds is required and must be at least 1";
        public static final String DOCKER_EXECUTION_PROFILE_ID_REQUIRED =
                "dockerExecutionProfileId is required";
        public static final String INVALID_METRICS_CONFIG_PREFIX = "Invalid metrics configuration: ";
        public static final String INVALID_TOOL_PREFIX = "Invalid tool: ";
        public static final String FAILED_UPLOAD_PREFIX = "Failed to upload file: ";
        public static final String EXTERNAL_BASE_URL_REQUIRED =
                "Маршрут EXTERNAL требует полный URL приёма пакета (baseUrl в записи summarizer_models).";
        public static final String EXTERNAL_BASE_URL_SCHEME =
                "baseUrl для EXTERNAL должен начинаться с http:// или https://";

        private Upload() {
        }

        public static String toolNotFound(String tool) {
            return "Tool '" + tool + "' not found in database. Please check available tools via GET /api/v1/loadtest/tools";
        }

        public static String toolDisabled(String tool) {
            return "Tool '" + tool + "' is disabled. Please enable it first or use another tool.";
        }

        public static String summarizerNotFound(String summarizer) {
            return "Summarizer '" + summarizer + "' not found. Use GET /api/v1/loadtest/summarizers?enabled=true";
        }

        public static String summarizerDisabled(String summarizer) {
            return "Summarizer '" + summarizer + "' is disabled. Enable it or use another.";
        }

        public static String fileExtensionRequired(String toolName, String supportedExtensions) {
            return String.format("File must have an extension. Tool '%s' supports extensions: %s",
                    toolName, supportedExtensions);
        }

        public static String fileExtensionMismatch(String toolName, String supportedExtensions, String actualExtension) {
            return String.format(
                    "File extension mismatch! Tool '%s' supports extensions: %s, but got '.%s'. "
                            + "Please ensure the file extension matches one of the supported extensions.",
                    toolName, supportedExtensions, actualExtension);
        }

        public static String fileTooLarge(long actualBytes, long maxBytes) {
            return String.format(
                    "File size %d bytes exceeds the maximum allowed scenario size of %d bytes (%s)",
                    actualBytes, maxBytes, formatBytesForMessage(maxBytes));
        }

        private static String formatBytesForMessage(long bytes) {
            if (bytes > 0 && bytes % (1024 * 1024) == 0) {
                return (bytes / (1024 * 1024)) + " MiB";
            }
            return String.format("%.2f MiB", bytes / (1024.0 * 1024.0));
        }
    }

    public static final class DockerProfile {
        public static final String OK = "OK";
        public static final String CREATED = "Profile created";
        public static final String UPDATED = "Profile updated";
        public static final String DELETED = "Profile deleted";

        public static final String PROFILE_NAME_REQUIRED = "Profile name is required";
        public static final String NO_PROFILE_IN_DATABASE = "No docker execution profile in database";
        public static final String CANNOT_DELETE_WITH_QUEUED_TASKS =
                "Нельзя удалить профиль: есть задачи в очереди, ссылающиеся на него";
        public static final String CANNOT_MUTATE_WHILE_QUEUE_HAS_TASKS =
                "Нельзя изменить профили Docker, пока в очереди есть неисполненные задачи (таблица test_task). "
                        + "Включите паузу очереди или дождитесь завершения прогонов.";

        public static final String DEFAULT_NETWORK_MODE = "loadtest_loadtest-network";
        public static final String DEFAULT_RESTART_POLICY = "no";
        public static final String DEFAULT_LOG_DRIVER = "json-file";
        public static final String DEFAULT_LOG_MAX_SIZE = "10m";

        private DockerProfile() {
        }

        public static String profileNotFound(UUID id) {
            return "Profile not found: " + id;
        }

        public static String profileNameAlreadyExists(String name) {
            return "Профиль с именем «" + name + "» уже существует";
        }

        public static String cannotDeleteSystemProfile(String name) {
            return "Нельзя удалить системный профиль «" + name + "»";
        }

        public static String invalidDockerExecutionProfileId(String param) {
            return "Invalid dockerExecutionProfileId: " + param;
        }

        public static String dockerExecutionProfileIdNotFound(UUID id) {
            return "dockerExecutionProfileId not found: " + id;
        }

        public static String dockerProfileDisabled(UUID id) {
            return "Docker profile is disabled: " + id;
        }
    }

    public static final class Tools {
        public static final String CREATED = "Tool created successfully";
        public static final String LIST_RETRIEVED = "Tools retrieved successfully";
        public static final String RETRIEVED = "Tool retrieved successfully";
        public static final String UPDATED = "Tool updated successfully";
        public static final String DELETED = "Tool deleted successfully";
        public static final String FAILED_CREATE_PREFIX = "Failed to create tool: ";
        public static final String FAILED_LIST_PREFIX = "Failed to get tools: ";
        public static final String FAILED_GET_PREFIX = "Failed to get tool: ";
        public static final String FAILED_UPDATE_PREFIX = "Failed to update tool: ";
        public static final String FAILED_DELETE_PREFIX = "Failed to delete tool: ";

        private Tools() {
        }

        public static String nameAlreadyExists(String name) {
            return "Tool with name '" + name + "' already exists";
        }

        public static String notFoundById(UUID id) {
            return "Tool with id '" + id + QUOTED_IDENTIFIER_NOT_FOUND_SUFFIX;
        }

        public static String notFoundByName(String name) {
            return "Tool with name '" + name + QUOTED_IDENTIFIER_NOT_FOUND_SUFFIX;
        }
    }

    public static final class Summarizers {
        public static final String CREATED = "Summarizer created successfully";
        public static final String LIST_RETRIEVED = "Summarizers retrieved successfully";
        public static final String RETRIEVED = "Summarizer retrieved successfully";
        public static final String UPDATED = "Summarizer updated successfully";
        public static final String DELETED = "Summarizer deleted successfully";
        public static final String FAILED_CREATE_PREFIX = "Failed to create summarizer: ";
        public static final String FAILED_LIST_PREFIX = "Failed to get summarizers: ";
        public static final String FAILED_GET_PREFIX = "Failed to get summarizer: ";
        public static final String FAILED_UPDATE_PREFIX = "Failed to update summarizer: ";
        public static final String FAILED_DELETE_PREFIX = "Failed to delete summarizer: ";

        private Summarizers() {
        }

        public static String nameAlreadyExists(String name) {
            return "Summarizer with name '" + name + "' already exists";
        }

        public static String notFoundById(UUID id) {
            return "Summarizer with id '" + id + QUOTED_IDENTIFIER_NOT_FOUND_SUFFIX;
        }

        public static String notFoundByName(String name) {
            return "Summarizer with name '" + name + QUOTED_IDENTIFIER_NOT_FOUND_SUFFIX;
        }

        public static final String MODEL_ID_REQUIRED_OPENAI = "Model ID is required for OPENAI provider";
    }

    public static final class Tasks {
        public static final String QUEUE_TASK_DELETED = "Задача удалена из очереди";
        public static final String TEST_QUEUED = "Тест поставлен в очередь";
        public static final String SUMMARIZATION_REQUESTED =
                "Суммаризация запрошена. Обновите страницу через несколько секунд.";
        public static final String REPORT_SAVED = "Отчёт сохранён";
        public static final String QUEUE_PAUSE_BODY = "Тело JSON должно содержать поле paused (boolean).";
        public static final String TASK_NOT_DELETABLE =
                "Нельзя удалить задачу: она уже выполняется (PROCESSING) или не в статусе ожидания.";
        public static final String EXTERNAL_DISPATCH_FAILED =
                "Не удалось отправить пакет во внешний контур (ingest). Проверьте base_url маршрута и доступность mock.";
        public static final String EXTERNAL_PACKAGE_DISPATCHED =
                "Пакет метрик и артефактов отправлен на ingest внешнего контура. После приёма (received=true) mock должен вызвать POST …/external-llm/summary. "
                        + "Ручной сценарий: GET /api/v1/loadtest/history/%s/external-llm/package.";

        private Tasks() {
        }
    }

    public static final class ExternalSummarization {
        public static final String RUN_NOT_FOUND = "Прогон не найден";
        public static final String SUMMARIZER_NAME_MISSING = "У прогона не задан summarizer_name";
        public static final String LLM_ROUTE_NOT_FOUND = "Маршрут LLM не найден";
        public static final String ROUTE_NOT_EXTERNAL = "Маршрут не EXTERNAL";
        public static final String ROUTE_NOT_EXTERNAL_USE_KAFKA =
                "Маршрут не EXTERNAL; используйте Kafka-суммаризацию";
        public static final String NO_ACTIVE_WINDOW =
                "Нет активного окна внешней суммаризации (ожидайте завершения сбора метрик или запросите суммаризацию повторно)";
        public static final String NO_ACTIVE_UPLOAD_WINDOW = "Нет активного окна для загрузки отчёта";
        public static final String WINDOW_EXPIRED = "Истекло окно внешней суммаризации";
        public static final String CALLBACK_WINDOW_EXPIRED = "Окно callback истекло";
        public static final String TEXT_REQUIRED = "Поле text обязательно";
        public static final String SUMMARY_DATA_SERIALIZATION_FAILED = "Не удалось сформировать summary_data";
        public static final String REPORT_STRUCTURE_HINT_RU =
                "Ожидаемый формат отчёта: секции ## Краткое содержание, ## Плюсы, ## Минусы, ## Предложения, ## Итог (как при вызове встроенного суммаризатора).";
        public static final String EXTERNAL_MODEL_ID = "external";
        public static final String EMPTY_SUMMARY_DATA_JSON = "{}";

        private ExternalSummarization() {
        }
    }
}
