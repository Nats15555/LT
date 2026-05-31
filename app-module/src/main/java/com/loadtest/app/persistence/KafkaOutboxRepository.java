package com.loadtest.app.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface KafkaOutboxRepository extends JpaRepository<KafkaOutboxEntity, UUID> {

    @Modifying
    @Transactional
    @Query(value = """
            CREATE TABLE IF NOT EXISTS kafka_outbox (
                id UUID PRIMARY KEY,
                module VARCHAR(32) NOT NULL,
                event_type VARCHAR(64) NOT NULL,
                topic VARCHAR(128) NOT NULL,
                event_key VARCHAR(128) NOT NULL,
                payload_json TEXT NOT NULL,
                status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
                attempts INTEGER NOT NULL DEFAULT 0,
                last_error TEXT,
                next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
            )
            """, nativeQuery = true)
    void ensureTable();

    @Modifying
    @Transactional
    @Query(value = """
            CREATE INDEX IF NOT EXISTS idx_kafka_outbox_retry
            ON kafka_outbox(module, status, next_attempt_at, created_at)
            """, nativeQuery = true)
    void ensureRetryIndex();

    List<KafkaOutboxEntity> findByModuleAndStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
            String module, String status, OffsetDateTime now, Pageable pageable);
}
