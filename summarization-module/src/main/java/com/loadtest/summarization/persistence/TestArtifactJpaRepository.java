package com.loadtest.summarization.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TestArtifactJpaRepository extends JpaRepository<TestArtifactEntity, UUID> {

    List<TestArtifactEntity> findByTaskIdOrderByFileName(UUID taskId);
}
