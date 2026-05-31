package com.loadtest.app.util;

public final class SummarizerRouteMessages {

    public static final String ROUTE_NOT_FOUND = "Маршрут LLM «%s» не найден.";
    public static final String ROUTE_DISABLED_RERUN =
            "Маршрут LLM выключен (enabled=false). Включите его или выберите другой.";
    public static final String ROUTE_DISABLED_SUMMARIZE =
            "Маршрут LLM выключен (enabled=false). Включите его в конфигурации или выберите другой.";
    public static final String EXTERNAL_BASE_URL_MISSING_RERUN =
            "У маршрута EXTERNAL не задан URL приёма пакета (baseUrl).";
    public static final String EXTERNAL_BASE_URL_MISSING_SUMMARIZE =
            "У маршрута EXTERNAL не задан полный URL приёма пакета (baseUrl в записи summarizer_models).";
    public static final String SUMMARIZER_REQUIRED =
            "Укажите маршрут LLM (summarizer) или запустите тест с выбранным маршрутом в форме /upload";

    private SummarizerRouteMessages() {
    }

    public static String routeNotFound(String summarizerName) {
        return ROUTE_NOT_FOUND.formatted(summarizerName);
    }
}
