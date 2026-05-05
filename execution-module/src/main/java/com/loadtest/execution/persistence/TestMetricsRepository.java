package com.loadtest.execution.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TestMetricsRepository extends JpaRepository<TestMetricsEntity, UUID> {
    List<TestMetricsEntity> findByTaskId(UUID taskId);
    List<TestMetricsEntity> findByTaskIdAndSourceType(UUID taskId, String sourceType);
}
