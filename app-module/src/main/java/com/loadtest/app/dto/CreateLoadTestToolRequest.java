package com.loadtest.app.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateLoadTestToolRequest {
    
    @NotBlank(message = "Name is required")
    @JsonProperty("name")
    private String name; // LOCUST, K6, JMETER

    @NotBlank(message = "Docker image is required")
    @JsonProperty("dockerImage")
    private String dockerImage;

    @NotEmpty(message = "File extensions are required")
    @JsonProperty("fileExtensions")
    private List<String> fileExtensions; // ['.py', '.js', '.jmx']

    @NotNull(message = "Enabled is required")
    @JsonProperty("enabled")
    private Boolean enabled;
}
