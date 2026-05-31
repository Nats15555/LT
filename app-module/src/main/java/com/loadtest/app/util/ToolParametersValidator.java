package com.loadtest.app.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class ToolParametersValidator {

    private static final Set<String> SYSTEM_PLACEHOLDERS = Set.of(
            "fileName", "reportBaseName", "metricsBaseName",
            "testFileHostPath", "reportsHostPath", "metricsHostPath"
    );

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{(\\w+)}");

    private final ObjectMapper objectMapper;

    public ToolParametersValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String validate(String toolParameters, String commandTemplate) {
        if (commandTemplate == null || commandTemplate.isBlank()) {
            return "commandTemplate is required";
        }
        Set<String> required = placeholdersInCommand(commandTemplate);
        required.removeAll(SYSTEM_PLACEHOLDERS);
        if (required.isEmpty()) {
            return null;
        }
        if (toolParameters == null || toolParameters.isBlank()) {
            return "Missing parameters for placeholders in command: " + String.join(", ", required);
        }
        try {
            JsonNode params = objectMapper.readTree(toolParameters);
            if (!params.isObject()) {
                return "toolParameters must be a JSON object (key-value)";
            }
            Set<String> missing = new HashSet<>();
            for (String key : required) {
                if (!params.has(key) || params.get(key).isNull()) {
                    missing.add(key);
                }
            }
            if (!missing.isEmpty()) {
                return "Missing parameters for placeholders in command: " + String.join(", ", missing);
            }
            return null;
        } catch (JsonProcessingException e) {
            log.debug("toolParameters parse error", e);
            return "Invalid toolParameters JSON: " + e.getMessage();
        }
    }

    public static Set<String> placeholdersInCommand(String commandTemplate) {
        Set<String> out = new HashSet<>();
        Matcher m = PLACEHOLDER_PATTERN.matcher(commandTemplate);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }
}
