package com.loadtest.app.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record CreateLoadTestToolRequest(
        @NotBlank(message = "Name is required")
        @JsonProperty("name") String name,
        @NotBlank(message = "Docker image is required")
        @JsonProperty("dockerImage") String dockerImage,
        @NotEmpty(message = "File extensions are required")
        @JsonProperty("fileExtensions") List<String> fileExtensions,
        @NotNull(message = "Enabled is required")
        @JsonProperty("enabled") Boolean enabled) {
}
