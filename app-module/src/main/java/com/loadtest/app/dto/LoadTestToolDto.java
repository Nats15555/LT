package com.loadtest.app.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record LoadTestToolDto(
        UUID id,
        String name,
        String dockerImage,
        List<String> fileExtensions,
        Boolean enabled,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
