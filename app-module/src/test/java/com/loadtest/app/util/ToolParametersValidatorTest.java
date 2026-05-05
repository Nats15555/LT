package com.loadtest.app.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ToolParametersValidatorTest {

    private ToolParametersValidator validator;

    @BeforeEach
    void setUp() {
        validator = new ToolParametersValidator(new ObjectMapper());
    }

    @Test
    void placeholdersInCommand_extractsKeys() {
        assertThat(ToolParametersValidator.placeholdersInCommand("run {host} {users} {fileName}"))
                .containsExactlyInAnyOrder("host", "users", "fileName");
    }

    @Test
    void validate_requiresCommandTemplate() {
        assertThat(validator.validate(null, null)).isEqualTo("commandTemplate is required");
        assertThat(validator.validate(null, "   ")).isEqualTo("commandTemplate is required");
    }

    @Test
    void validate_okWhenOnlySystemPlaceholders() {
        assertThat(validator.validate(null, "k6 run {fileName} --out {reportBaseName}")).isNull();
    }

    @Test
    void validate_requiresParamsWhenNonSystemPlaceholdersMissing() {
        assertThat(validator.validate(null, "run {host}"))
                .contains("Missing parameters").contains("host");
        assertThat(validator.validate("", "run {host}"))
                .contains("host");
    }

    @Test
    void validate_okWhenJsonContainsKeys() {
        assertThat(validator.validate("{\"host\":\"http://x\"}", "run {host}")).isNull();
    }

    @Test
    void validate_rejectsNonObjectJson() {
        assertThat(validator.validate("[1]", "run {host}")).contains("JSON object");
    }

    @Test
    void validate_rejectsMissingOrNullKeys() {
        assertThat(validator.validate("{\"host\":null}", "run {host}")).contains("host");
        assertThat(validator.validate("{}", "run {host}")).contains("host");
    }

    @Test
    void validate_invalidJsonReturnsMessage() {
        assertThat(validator.validate("{", "run {host}")).contains("Invalid toolParameters JSON");
    }

    @Test
    void placeholdersInCommand_emptyWhenNone() {
        assertThat(ToolParametersValidator.placeholdersInCommand("plain")).isEqualTo(Set.of());
    }
}
