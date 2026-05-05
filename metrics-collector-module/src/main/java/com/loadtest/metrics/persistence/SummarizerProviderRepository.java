package com.loadtest.metrics.persistence;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Slf4j
@Repository
public class SummarizerProviderRepository {

    private final JdbcTemplate jdbcTemplate;

    public SummarizerProviderRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<String> findProviderBySummarizerName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        try {
            return jdbcTemplate.query(
                    "SELECT provider FROM summarizer_models WHERE name = ? LIMIT 1",
                    rs -> rs.next() ? Optional.ofNullable(rs.getString("provider")) : Optional.empty(),
                    name.trim());
        } catch (Exception e) {
            log.warn("Failed to load summarizer provider for name={}: {}", name, e.getMessage());
            return Optional.empty();
        }
    }

    public boolean isSummarizerEnabled(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        try {
            Boolean v = jdbcTemplate.query(
                    "SELECT enabled FROM summarizer_models WHERE name = ? LIMIT 1",
                    rs -> rs.next() ? rs.getBoolean("enabled") : false,
                    name.trim());
            return Boolean.TRUE.equals(v);
        } catch (Exception e) {
            return false;
        }
    }
}
