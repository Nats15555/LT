package com.loadtest.metrics.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TestMetricsJpaRepository extends JpaRepository<TestMetricsEntity, UUID> {
}
