package com.loadtest.metrics.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SummarizerProviderRepositoryTest {

    @Mock
    private SummarizerModelJpaRepository summarizerModelJpaRepository;

    private SummarizerProviderRepository repository;

    @BeforeEach
    void setUp() {
        repository = new SummarizerProviderRepository(summarizerModelJpaRepository);
    }

    @Test
    void findProvider_branches() {
        assertThat(repository.findProviderBySummarizerName(null)).isEmpty();
        assertThat(repository.findProviderBySummarizerName("  ")).isEmpty();

        when(summarizerModelJpaRepository.findByName("route")).thenReturn(Optional.of(model(true)));
        assertThat(repository.findProviderBySummarizerName("route")).contains("EXTERNAL");

        when(summarizerModelJpaRepository.findByName("missing")).thenReturn(Optional.empty());
        assertThat(repository.findProviderBySummarizerName("missing")).isEmpty();

        when(summarizerModelJpaRepository.findByName("route")).thenThrow(new RuntimeException("db"));
        assertThat(repository.findProviderBySummarizerName("route")).isEmpty();
    }

    @Test
    void isEnabled_branches() {
        assertThat(repository.isSummarizerEnabled(null)).isFalse();
        assertThat(repository.isSummarizerEnabled(" ")).isFalse();

        when(summarizerModelJpaRepository.findByName("route")).thenReturn(Optional.of(model(true)));
        assertThat(repository.isSummarizerEnabled("route")).isTrue();

        when(summarizerModelJpaRepository.findByName("route")).thenReturn(Optional.of(model(false)));
        assertThat(repository.isSummarizerEnabled("route")).isFalse();

        when(summarizerModelJpaRepository.findByName("missing")).thenReturn(Optional.empty());
        assertThat(repository.isSummarizerEnabled("missing")).isFalse();

        when(summarizerModelJpaRepository.findByName("route")).thenThrow(new RuntimeException("db"));
        assertThat(repository.isSummarizerEnabled("route")).isFalse();
    }

    private static SummarizerModelEntity model(boolean enabled) {
        OffsetDateTime now = OffsetDateTime.now();
        return SummarizerModelEntity.builder()
                .id(UUID.randomUUID())
                .name("route")
                .provider("EXTERNAL")
                .modelId("m")
                .enabled(enabled)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}
