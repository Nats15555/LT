package com.loadtest.app.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DockerExecutionProfileRepository extends JpaRepository<DockerExecutionProfileEntity, UUID> {

    Optional<DockerExecutionProfileEntity> findByName(String name);

    boolean existsByName(String name);

    List<DockerExecutionProfileEntity> findAllByOrderByNameAsc();

    List<DockerExecutionProfileEntity> findAllByEnabledTrueOrderByNameAsc();

    Optional<DockerExecutionProfileEntity> findFirstByNameAndEnabledTrue(String name);

    Optional<DockerExecutionProfileEntity> findFirstByEnabledTrueOrderByCreatedAtAsc();
}
