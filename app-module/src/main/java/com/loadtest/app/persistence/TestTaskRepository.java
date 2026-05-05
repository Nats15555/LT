package com.loadtest.app.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

public interface TestTaskRepository extends JpaRepository<TestTaskEntity, UUID> {

    long countByDockerExecutionProfileId(java.util.UUID dockerExecutionProfileId);

    List<TestTaskEntity> findAllByOrderByCreatedAtDesc();
    Page<TestTaskEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM TestTaskEntity t WHERE t.id = :id AND t.status = 'PENDING'")
    int deleteByIdIfStatusPending(@Param("id") UUID id);
}
