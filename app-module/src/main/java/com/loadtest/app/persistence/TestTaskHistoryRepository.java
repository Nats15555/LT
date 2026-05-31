package com.loadtest.app.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TestTaskHistoryRepository extends JpaRepository<TestTaskHistoryEntity, UUID> {

    Page<TestTaskHistoryEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
