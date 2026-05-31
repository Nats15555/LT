package com.loadtest.app.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface TestTaskKafkaPendingRepository extends JpaRepository<TestTaskKafkaPendingEntity, UUID> {

    @Modifying
    @Transactional
    @Query(value = """
            CREATE TABLE IF NOT EXISTS test_task_kafka_pending (
                task_id UUID PRIMARY KEY REFERENCES test_task(id) ON DELETE CASCADE,
                created_at TIMESTAMPTZ NOT NULL DEFAULT now()
            )
            """, nativeQuery = true)
    void ensureTable();

    @Modifying
    @Transactional
    @Query(value = """
            CREATE INDEX IF NOT EXISTS idx_test_task_kafka_pending_created_at
            ON test_task_kafka_pending(created_at)
            """, nativeQuery = true)
    void ensureIndex();

    List<TestTaskKafkaPendingEntity> findByOrderByCreatedAtAsc(Pageable pageable);
}
