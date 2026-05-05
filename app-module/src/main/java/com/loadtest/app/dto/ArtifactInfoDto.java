package com.loadtest.app.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArtifactInfoDto {
    private UUID id;
    private String fileName;
    private Long originalSizeBytes;
}
