package com.loadtest.execution.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DockerExecutionProfileRepository extends JpaRepository<DockerExecutionProfileEntity, UUID> {

    Optional<DockerExecutionProfileEntity> findByIdAndEnabledTrue(UUID id);
}
