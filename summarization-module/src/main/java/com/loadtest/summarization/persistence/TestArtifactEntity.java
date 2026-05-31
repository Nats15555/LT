package com.loadtest.summarization.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "test_artifacts")
public class TestArtifactEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "task_id", nullable = false)
    private UUID taskId;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "file_content", nullable = false)
    private byte[] fileContent;

    @Column(name = "content_encoding", nullable = false)
    private String contentEncoding;

    @Column(name = "original_size_bytes")
    private Long originalSizeBytes;

    @Column(name = "compressed_size_bytes")
    private Long compressedSizeBytes;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
