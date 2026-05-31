package com.loadtest.app.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component
public class MetricsConfigSchemaValidator {

    private JsonSchema jsonSchema;

    @PostConstruct
    void loadSchema() throws Exception {
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
        ClassPathResource resource = new ClassPathResource(ClasspathResources.METRICS_CONFIG_SCHEMA);
        try (InputStream in = resource.getInputStream()) {
            jsonSchema = factory.getSchema(in);
        }
        log.info("Loaded metrics config JSON Schema from {}", resource.getPath());
    }

    public void validate(JsonNode instance) {
        Set<ValidationMessage> errors = jsonSchema.validate(instance);
        if (errors.isEmpty()) {
            return;
        }
        String detail = errors.stream()
                .map(ValidationMessage::getMessage)
                .sorted()
                .collect(Collectors.joining("; "));
        throw new IllegalArgumentException(detail);
    }
}
