package com.loadtest.metrics.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SummarizerModelJpaRepository extends JpaRepository<SummarizerModelEntity, UUID> {

    Optional<SummarizerModelEntity> findByName(String name);
}
