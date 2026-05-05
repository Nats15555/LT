package com.loadtest.app.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TestArtifactRepository extends JpaRepository<TestArtifactEntity, UUID> {

    List<TestArtifactEntity> findByTaskIdOrderByFileName(UUID taskId);

    Optional<TestArtifactEntity> findByTaskIdAndFileName(UUID taskId, String fileName);
}
