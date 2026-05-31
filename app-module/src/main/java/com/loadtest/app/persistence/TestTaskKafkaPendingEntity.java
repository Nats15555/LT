package com.loadtest.app.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "test_task_kafka_pending")
public class TestTaskKafkaPendingEntity {

    @Id
    @Column(name = "task_id", nullable = false)
    private UUID taskId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
