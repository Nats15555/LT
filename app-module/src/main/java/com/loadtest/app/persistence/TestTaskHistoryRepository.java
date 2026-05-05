package com.loadtest.app.persistence;

import com.loadtest.app.dto.TaskHistoryItemDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface TestTaskHistoryRepository extends JpaRepository<TestTaskHistoryEntity, UUID> {

    List<TestTaskHistoryEntity> findAllByOrderByCreatedAtDesc();
    Page<TestTaskHistoryEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @Query("SELECT new com.loadtest.app.dto.TaskHistoryItemDto(e.id, e.finalStatus, e.testTool, e.testFileName, e.summarizerName, e.command, e.createdAt, e.startedAt, e.finishedAt, e.errorMessage, CASE WHEN e.metricsConfig IS NOT NULL THEN true ELSE false END, null, null, e.dockerProfileName) FROM TestTaskHistoryEntity e ORDER BY e.createdAt DESC")
    List<TaskHistoryItemDto> findAllHistoryDtos();
}
