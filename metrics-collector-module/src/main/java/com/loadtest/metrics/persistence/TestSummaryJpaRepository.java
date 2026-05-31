package com.loadtest.metrics.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface TestSummaryJpaRepository extends JpaRepository<TestSummaryEntity, UUID> {

    List<TestSummaryEntity> findByTaskIdAndProcessingStatus(UUID taskId, String processingStatus);

    @Modifying
    @Transactional
    @Query("delete from TestSummaryEntity t where t.taskId = :taskId and t.processingStatus = :status")
    void deleteByTaskIdAndProcessingStatus(@Param("taskId") UUID taskId, @Param("status") String status);
}
