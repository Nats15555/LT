package com.loadtest.app.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {MetricsConfigSchemaValidator.class, MetricsConfigSchemaValidatorTest.Cfg.class})
class MetricsConfigSchemaValidatorTest {

    @Configuration
    static class Cfg {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @Autowired
    private MetricsConfigSchemaValidator validator;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void validate_acceptsMinimalValidConfig() throws Exception {
        JsonNode node = objectMapper.readTree("{\"requests\":[{\"url\":\"http://localhost:9090/metrics\"}]}");
        assertThatCode(() -> validator.validate(node)).doesNotThrowAnyException();
    }

    @Test
    void validate_rejectsInvalidConfig() throws Exception {
        JsonNode node = objectMapper.readTree("{\"requests\":[]}");
        assertThatThrownBy(() -> validator.validate(node))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requests");
    }

    @Test
    void loadSchema_throwsWhenSchemaResourceCannotBeRead() {
        try (MockedConstruction<org.springframework.core.io.ClassPathResource> ignored =
                     Mockito.mockConstruction(org.springframework.core.io.ClassPathResource.class,
                             (mock, context) -> Mockito.when(mock.getInputStream()).thenThrow(new IOException("io")))) {
            MetricsConfigSchemaValidator fresh = new MetricsConfigSchemaValidator();
            assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(fresh, "loadSchema"))
                    .hasRootCauseInstanceOf(IOException.class)
                    .hasRootCauseMessage("io");
        }
    }

    @Test
    void loadSchema_successPath_coversTryWithResources() throws Exception {
        MetricsConfigSchemaValidator fresh = new MetricsConfigSchemaValidator();
        ReflectionTestUtils.invokeMethod(fresh, "loadSchema");
        JsonNode ok = objectMapper.readTree("{\"requests\":[{\"url\":\"http://localhost:9090/metrics\"}]}");
        assertThatCode(() -> fresh.validate(ok)).doesNotThrowAnyException();

        JsonNode bad = objectMapper.readTree("{\"requests\":[]}");
        assertThatThrownBy(() -> fresh.validate(bad)).isInstanceOf(IllegalArgumentException.class);
        assertThat(ReflectionTestUtils.getField(fresh, "jsonSchema")).isNotNull();
    }

    @Test
    void loadSchema_throwsWhenCloseFails_afterSchemaRead() {
        String minimalSchema = "{\"$schema\":\"http://json-schema.org/draft-07/schema#\",\"type\":\"object\"}";
        byte[] schemaBytes = minimalSchema.getBytes(StandardCharsets.UTF_8);
        InputStream brokenClose = new java.io.ByteArrayInputStream(schemaBytes) {
            @Override
            public void close() throws IOException {
                super.close();
                throw new IOException("close-failed");
            }
        };

        try (MockedConstruction<org.springframework.core.io.ClassPathResource> ignored =
                     Mockito.mockConstruction(org.springframework.core.io.ClassPathResource.class,
                             (mock, context) -> Mockito.when(mock.getInputStream()).thenReturn(brokenClose))) {
            MetricsConfigSchemaValidator fresh = new MetricsConfigSchemaValidator();
            assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(fresh, "loadSchema"))
                    .hasRootCauseInstanceOf(IOException.class)
                    .hasRootCauseMessage("close-failed");
        }
    }

    @Test
    void loadSchema_throwsWhenInputStreamIsNull() {
        try (MockedConstruction<org.springframework.core.io.ClassPathResource> ignored =
                     Mockito.mockConstruction(org.springframework.core.io.ClassPathResource.class,
                             (mock, context) -> Mockito.when(mock.getInputStream()).thenReturn(null))) {
            MetricsConfigSchemaValidator fresh = new MetricsConfigSchemaValidator();
            assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(fresh, "loadSchema"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("argument \"in\" is null");
        }
    }

    @Test
    void loadSchema_throwsWhenStreamReadFails_butCloseSucceeds() {
        InputStream brokenRead = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("read-failed");
            }
        };
        try (MockedConstruction<org.springframework.core.io.ClassPathResource> ignored =
                     Mockito.mockConstruction(org.springframework.core.io.ClassPathResource.class,
                             (mock, context) -> Mockito.when(mock.getInputStream()).thenReturn(brokenRead))) {
            MetricsConfigSchemaValidator fresh = new MetricsConfigSchemaValidator();
            assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(fresh, "loadSchema"))
                    .hasRootCauseInstanceOf(IOException.class)
                    .hasRootCauseMessage("read-failed");
        }
    }

    @Test
    void loadSchema_successWithCustomStream_closesResourceBranch() {
        String minimalSchema = "{\"$schema\":\"http://json-schema.org/draft-07/schema#\",\"type\":\"object\"}";
        byte[] schemaBytes = minimalSchema.getBytes(StandardCharsets.UTF_8);
        AtomicBoolean closed = new AtomicBoolean(false);
        InputStream tracked = new java.io.ByteArrayInputStream(schemaBytes) {
            @Override
            public void close() throws IOException {
                closed.set(true);
                super.close();
            }
        };

        try (MockedConstruction<org.springframework.core.io.ClassPathResource> ignored =
                     Mockito.mockConstruction(org.springframework.core.io.ClassPathResource.class,
                             (mock, context) -> Mockito.when(mock.getInputStream()).thenReturn(tracked))) {
            MetricsConfigSchemaValidator fresh = new MetricsConfigSchemaValidator();
            ReflectionTestUtils.invokeMethod(fresh, "loadSchema");
            assertThat(closed.get()).isTrue();
            assertThat(ReflectionTestUtils.getField(fresh, "jsonSchema")).isNotNull();
        }
    }
}
