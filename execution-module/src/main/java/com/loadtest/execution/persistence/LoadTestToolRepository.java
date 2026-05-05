package com.loadtest.execution.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LoadTestToolRepository extends JpaRepository<LoadTestToolEntity, java.util.UUID> {

    Optional<LoadTestToolEntity> findByNameAndEnabledTrue(String name);
}
