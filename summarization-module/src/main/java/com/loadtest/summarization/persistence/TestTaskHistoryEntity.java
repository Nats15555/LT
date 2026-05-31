package com.loadtest.summarization.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "test_task_history")
public class TestTaskHistoryEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "final_status", nullable = false)
    private String finalStatus;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    @Column(name = "moved_at", nullable = false)
    private OffsetDateTime movedAt;

    @Column(name = "test_tool", nullable = false)
    private String testTool;

    @Column(name = "test_file_name", nullable = false)
    private String testFileName;

    @Column(name = "test_file_content_base64", nullable = false, columnDefinition = "TEXT")
    private String testFileContentBase64;

    @Column(name = "command", nullable = false, columnDefinition = "TEXT")
    private String command;

    @Column(name = "expected_duration_seconds")
    private Integer expectedDurationSeconds;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metrics_config", columnDefinition = "jsonb")
    private String metricsConfig;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "summarizer_name", length = 64)
    private String summarizerName;

    @Column(name = "docker_execution_profile_id")
    private UUID dockerExecutionProfileId;

    @Column(name = "docker_profile_name", length = 128)
    private String dockerProfileName;
}
