package com.loadtest.app.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SummarizerModelRepository extends JpaRepository<SummarizerModelEntity, UUID> {
    Optional<SummarizerModelEntity> findByName(String name);

    List<SummarizerModelEntity> findByEnabledTrue();

    boolean existsByName(String name);
}
