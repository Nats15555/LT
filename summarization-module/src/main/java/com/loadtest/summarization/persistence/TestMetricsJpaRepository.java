package com.loadtest.summarization.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TestMetricsJpaRepository extends JpaRepository<TestMetricsEntity, UUID> {

    List<TestMetricsEntity> findByTaskIdOrderByCollectedAtAsc(UUID taskId);
}
