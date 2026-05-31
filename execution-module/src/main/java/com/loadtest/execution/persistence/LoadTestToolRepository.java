package com.loadtest.execution.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface LoadTestToolRepository extends JpaRepository<LoadTestToolEntity, UUID> {

    Optional<LoadTestToolEntity> findByNameAndEnabledTrue(String name);
}
