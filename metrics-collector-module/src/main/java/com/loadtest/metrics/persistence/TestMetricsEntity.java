package com.loadtest.metrics.persistence;

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
@Table(name = "test_metrics")
public class TestMetricsEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "task_id", nullable = false)
    private UUID taskId;

    @Column(name = "source_type", nullable = false)
    private String sourceType;

    @Column(name = "endpoint_url", nullable = false)
    private String endpointUrl;

    @Column(name = "query_params", columnDefinition = "TEXT")
    private String queryParams;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metrics_data", nullable = false, columnDefinition = "jsonb")
    private String metricsData;

    @Column(name = "collected_at", nullable = false)
    private OffsetDateTime collectedAt;
}
