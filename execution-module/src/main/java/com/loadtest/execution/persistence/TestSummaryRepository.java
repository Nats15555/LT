package com.loadtest.execution.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TestSummaryRepository extends JpaRepository<TestSummaryEntity, UUID> {
    Optional<TestSummaryEntity> findByTaskId(UUID taskId);
    List<TestSummaryEntity> findByTaskIdAndSummaryType(UUID taskId, String summaryType);
    List<TestSummaryEntity> findByProcessingStatus(String processingStatus);
}
