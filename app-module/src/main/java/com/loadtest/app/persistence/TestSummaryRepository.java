package com.loadtest.app.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TestSummaryRepository extends JpaRepository<TestSummaryEntity, UUID> {
    List<TestSummaryEntity> findByTaskIdOrderByProcessedAtDesc(UUID taskId);

    List<TestSummaryEntity> findByTaskIdAndProcessingStatusOrderByCreatedAtDesc(UUID taskId, String processingStatus);

    Optional<TestSummaryEntity> findFirstByTaskIdAndProcessingStatusOrderByCreatedAtDesc(UUID taskId, String processingStatus);

    @Modifying
    @Transactional
    @Query("delete from TestSummaryEntity t where t.taskId = :taskId and t.processingStatus = :status")
    int deleteByTaskIdAndProcessingStatus(@Param("taskId") UUID taskId, @Param("status") String status);
}
