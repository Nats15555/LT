package com.loadtest.execution.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TestArtifactRepository extends JpaRepository<TestArtifactEntity, UUID> {
}
