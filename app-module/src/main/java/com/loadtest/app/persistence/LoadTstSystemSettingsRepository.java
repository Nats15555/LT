package com.loadtest.app.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface LoadTstSystemSettingsRepository extends JpaRepository<LoadTestSystemSettingsEntity, Integer> {

    @Modifying
    @Transactional
    @Query(value = """
            CREATE TABLE IF NOT EXISTS loadtest_system_settings (
                id INTEGER PRIMARY KEY DEFAULT 1 CHECK (id = 1),
                queue_paused BOOLEAN NOT NULL DEFAULT false,
                updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
            )
            """, nativeQuery = true)
    void ensureTable();

    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO loadtest_system_settings (id, queue_paused) VALUES (1, false)
            ON CONFLICT (id) DO NOTHING
            """, nativeQuery = true)
    void ensureDefaultRow();
}
