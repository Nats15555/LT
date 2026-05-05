package com.loadtest.app.persistence;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "load_test_tools")
public class LoadTestToolEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "name", nullable = false, unique = true, length = 64)
    private String name; // LOCUST, K6, JMETER

    @Column(name = "docker_image", nullable = false, length = 256)
    private String dockerImage;

    @Column(name = "file_extensions", nullable = false)
    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<String> fileExtensions; // ['.py', '.js', '.jmx']

    @Column(name = "enabled", nullable = false)
    private Boolean enabled;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
