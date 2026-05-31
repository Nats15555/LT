package com.loadtest.summarization.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TestTaskHistoryJpaRepository extends JpaRepository<TestTaskHistoryEntity, UUID> {
}
