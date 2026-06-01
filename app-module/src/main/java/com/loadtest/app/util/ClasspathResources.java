package com.loadtest.app.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class ClasspathResources {

    public static final String METRICS_CONFIG_SCHEMA = "schemas/metrics-config.schema.json";
    public static final String STANDARD_SUMMARIZATION_PROMPT_TEMPLATE =
            "prompts/standard-summarization-prompt-template.txt";

    private ClasspathResources() {
    }

    public static String readUtf8(String classpathResource) {
        InputStream in = ClasspathResources.class.getClassLoader().getResourceAsStream(classpathResource);
        if (in == null) {
            throw new IllegalStateException("Classpath resource not found: " + classpathResource);
        }
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read resource: " + classpathResource, e);
        }
    }
}
