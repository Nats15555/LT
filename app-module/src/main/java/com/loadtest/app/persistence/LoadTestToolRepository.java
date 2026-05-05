package com.loadtest.app.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LoadTestToolRepository extends JpaRepository<LoadTestToolEntity, UUID> {
    Optional<LoadTestToolEntity> findByName(String name);
    List<LoadTestToolEntity> findByEnabledTrue();
    boolean existsByName(String name);
}
