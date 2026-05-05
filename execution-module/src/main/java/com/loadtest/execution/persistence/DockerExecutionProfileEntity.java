package com.loadtest.execution.persistence;

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

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "docker_execution_profile")
public class DockerExecutionProfileEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "docker_host_uri", columnDefinition = "TEXT")
    private String dockerHostUri;

    @Column(name = "named_volume_for_child_binds", length = 512)
    private String namedVolumeForChildBinds;

    @Column(name = "memory_limit_mb")
    private Integer memoryLimitMb;

    @Column(name = "memory_reservation_mb")
    private Integer memoryReservationMb;

    @Column(name = "cpu_limit", precision = 5, scale = 2)
    private BigDecimal cpuLimit;

    @Column(name = "cpu_shares")
    private Integer cpuShares;

    @Column(name = "max_concurrent_containers", nullable = false)
    private Integer maxConcurrentContainers;

    @Column(name = "network_mode", length = 64)
    private String networkMode;

    @Column(name = "restart_policy", length = 32)
    private String restartPolicy;

    @Column(name = "restart_max_retries")
    private Integer restartMaxRetries;

    @Column(name = "log_driver", length = 32)
    private String logDriver;

    @Column(name = "log_max_size", length = 16)
    private String logMaxSize;

    @Column(name = "log_max_files")
    private Integer logMaxFiles;

    @Column(name = "environment_variables")
    @JdbcTypeCode(SqlTypes.JSON)
    private String environmentVariables;

    @Column(name = "labels")
    @JdbcTypeCode(SqlTypes.JSON)
    private String labels;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
