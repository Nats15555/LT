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

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "loadtest_system_settings")
public class LoadTestSystemSettingsEntity {

    @Id
    @Column(name = "id", nullable = false)
    private Integer id;

    @Column(name = "queue_paused", nullable = false)
    private Boolean queuePaused;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
