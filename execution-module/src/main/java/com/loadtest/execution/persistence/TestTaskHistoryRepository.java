package com.loadtest.execution.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TestTaskHistoryRepository extends JpaRepository<TestTaskHistoryEntity, UUID> {
}

