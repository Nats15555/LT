package com.loadtest.app.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record UpdateLoadTestToolRequest(
        @JsonProperty("dockerImage") String dockerImage,
        @JsonProperty("fileExtensions") List<String> fileExtensions,
        @JsonProperty("enabled") Boolean enabled) {
}
