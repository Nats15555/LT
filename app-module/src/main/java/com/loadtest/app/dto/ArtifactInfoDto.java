package com.loadtest.app.dto;

import java.util.UUID;

public record ArtifactInfoDto(UUID id, String fileName, Long originalSizeBytes) {
}
