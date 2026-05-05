package com.loadtest.app.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadtest.app.dto.TestTaskMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;

@ExtendWith(MockitoExtension.class)
class MetricsConfigParserTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private MetricsConfigSchemaValidator schemaValidator;

    private MetricsConfigParser parser;

    @BeforeEach
    void setUp() {
        parser = new MetricsConfigParser(objectMapper, schemaValidator);
    }

    @Test
    void parse_rejectsBlank() {
        assertThatThrownBy(() -> parser.parseMetricsConfigRequests(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parseMetricsConfigRequests("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parse_normalizesDelayNameMethod() throws Exception {
        doNothing().when(schemaValidator).validate(any());
        String json = """
                {"delaySeconds":3,"requests":[{"url":"http://x","method":"","name":""}]}
                """;
        TestTaskMessage.MetricsConfig cfg = parser.parseMetricsConfigRequests(json);
        assertThat(cfg.getDelaySeconds()).isEqualTo(3);
        assertThat(cfg.getRequests()).hasSize(1);
        assertThat(cfg.getRequests().get(0).getMethod()).isEqualTo("GET");
        assertThat(cfg.getRequests().get(0).getName()).isEqualTo("http://x");
    }

    @Test
    void parse_defaultDelayWhenNull() throws Exception {
        doNothing().when(schemaValidator).validate(any());
        String json = """
                {"requests":[{"url":"http://y"}]}
                """;
        TestTaskMessage.MetricsConfig cfg = parser.parseMetricsConfigRequests(json);
        assertThat(cfg.getDelaySeconds()).isEqualTo(0);
    }

    @Test
    void parse_rejectsEmptyRequestsAfterSchema() throws Exception {
        doNothing().when(schemaValidator).validate(any());
        String json = "{\"requests\":null}";
        assertThatThrownBy(() -> parser.parseMetricsConfigRequests(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requests");
    }

    @Test
    void parse_rejectsEmptyRequestArrayAfterSchema() throws Exception {
        doNothing().when(schemaValidator).validate(any());
        assertThatThrownBy(() -> parser.parseMetricsConfigRequests("{\"requests\":[]}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requests");
    }

    @Test
    void parse_rejectsMissingUrl() throws Exception {
        doNothing().when(schemaValidator).validate(any());
        String json = "{\"requests\":[{\"name\":\"n\"}]}";
        assertThatThrownBy(() -> parser.parseMetricsConfigRequests(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("url");
    }

    @Test
    void parse_invalidJsonFailsBeforeValidation() {
        assertThatThrownBy(() -> parser.parseMetricsConfigRequests("{x"))
                .isInstanceOf(Exception.class);
    }

    @Test
    void parse_normalizesNullDelayAndBlankNameMethod() throws Exception {
        doNothing().when(schemaValidator).validate(any());
        String json = """
                {"delaySeconds":null,"requests":[{"url":"http://u","name":"  ","method":"  "}]}
                """;
        TestTaskMessage.MetricsConfig cfg = parser.parseMetricsConfigRequests(json);
        assertThat(cfg.getDelaySeconds()).isEqualTo(0);
        assertThat(cfg.getRequests().get(0).getName()).isEqualTo("http://u");
        assertThat(cfg.getRequests().get(0).getMethod()).isEqualTo("GET");
    }

    @Test
    void parse_rejectsWhitespaceOnlyUrl() {
        doNothing().when(schemaValidator).validate(any());
        String json = """
                {"requests":[{"url":"   "}]}
                """;
        assertThatThrownBy(() -> parser.parseMetricsConfigRequests(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("url");
    }

    @Test
    void parse_keepsExplicitNameAndMethod() throws Exception {
        doNothing().when(schemaValidator).validate(any());
        String json = """
                {"delaySeconds":1,"requests":[{"url":"http://z","name":"src","method":"POST"}]}
                """;
        TestTaskMessage.MetricsConfig cfg = parser.parseMetricsConfigRequests(json);
        assertThat(cfg.getDelaySeconds()).isEqualTo(1);
        assertThat(cfg.getRequests().get(0).getName()).isEqualTo("src");
        assertThat(cfg.getRequests().get(0).getMethod()).isEqualTo("POST");
    }

    @Test
    void parse_defaultsMethodWhenNull() throws Exception {
        doNothing().when(schemaValidator).validate(any());
        String json = """
                {"requests":[{"url":"http://z","name":"src","method":null}]}
                """;
        TestTaskMessage.MetricsConfig cfg = parser.parseMetricsConfigRequests(json);
        assertThat(cfg.getRequests().get(0).getMethod()).isEqualTo("GET");
    }
}
