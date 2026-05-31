package com.loadtest.metrics.persistence;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Slf4j
@Repository
@RequiredArgsConstructor
public class SummarizerProviderRepository {

    private final SummarizerModelJpaRepository summarizerModelJpaRepository;

    public Optional<String> findProviderBySummarizerName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        try {
            return summarizerModelJpaRepository.findByName(name.trim()).map(SummarizerModelEntity::getProvider);
        } catch (RuntimeException e) {
            log.warn("Failed to load summarizer provider for name={}: {}", name, e.getMessage());
            return Optional.empty();
        }
    }

    public boolean isSummarizerEnabled(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        try {
            return summarizerModelJpaRepository.findByName(name.trim())
                    .map(SummarizerModelEntity::getEnabled)
                    .map(Boolean.TRUE::equals)
                    .orElse(false);
        } catch (RuntimeException e) {
            return false;
        }
    }
}
