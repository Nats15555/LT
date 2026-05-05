package com.loadtest.metrics.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadtest.metrics.dto.MetricsCollectionEvent;
import com.loadtest.metrics.persistence.TaskMetricsConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MetricsCollectionRequestBuilderTest {

    @Mock
    private TaskMetricsConfigRepository repo;

    private MetricsCollectionRequestBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new MetricsCollectionRequestBuilder(repo, new ObjectMapper());
    }

    @Test
    void tryBuildFromEvent_branches() {
        MetricsCollectionEvent event = new MetricsCollectionEvent(UUID.randomUUID().toString(), 1000L, 2000L);

        when(repo.findByTaskId(any())).thenReturn(Optional.empty());
        assertThat(builder.tryBuildFromEvent(event)).isEmpty();

        when(repo.findByTaskId(any())).thenReturn(Optional.of(new TaskMetricsConfigRepository.TaskMetricsConfig(" ")));
        assertThat(builder.tryBuildFromEvent(event)).isEmpty();

        when(repo.findByTaskId(any())).thenReturn(Optional.of(new TaskMetricsConfigRepository.TaskMetricsConfig("{bad")));
        assertThat(builder.tryBuildFromEvent(event)).isEmpty();

        when(repo.findByTaskId(any())).thenReturn(Optional.of(new TaskMetricsConfigRepository.TaskMetricsConfig("{\"requests\":[]}")));
        assertThat(builder.tryBuildFromEvent(event)).isEmpty();

        when(repo.findByTaskId(any())).thenReturn(Optional.of(new TaskMetricsConfigRepository.TaskMetricsConfig(
                "{\"delaySeconds\":2,\"requests\":[{\"name\":\"n\",\"method\":\"GET\",\"url\":\"http://u\",\"queryParams\":\"a=1\"}]}"
        )));
        var ok = builder.tryBuildFromEvent(event);
        assertThat(ok).isPresent();
        assertThat(ok.get().getDelaySeconds()).isEqualTo(2);
        assertThat(ok.get().getRequests()).hasSize(1);
    }

    @Test
    void buildFromEvent_throwsWhenEmpty() {
        MetricsCollectionEvent event = new MetricsCollectionEvent(UUID.randomUUID().toString(), 1L, 2L);
        when(repo.findByTaskId(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> builder.buildFromEvent(event)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tryBuildFromEvent_invalidUuid_throws() {
        MetricsCollectionEvent bad = new MetricsCollectionEvent("not-uuid", 1L, 2L);
        assertThatThrownBy(() -> builder.tryBuildFromEvent(bad)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tryBuildFromEvent_defaultDelayWhenMissing() {
        MetricsCollectionEvent event = new MetricsCollectionEvent(UUID.randomUUID().toString(), 1L, 2L);
        when(repo.findByTaskId(any())).thenReturn(Optional.of(
                new TaskMetricsConfigRepository.TaskMetricsConfig("{\"requests\":[{\"url\":\"http://u\"}]}")
        ));
        var built = builder.tryBuildFromEvent(event);
        assertThat(built).isPresent();
        assertThat(built.get().getDelaySeconds()).isEqualTo(0);
    }

    @Test
    void tryBuildFromEvent_emptyWhenMetricsConfigJsonIsNull_hitsLine55NullBranch() {
        MetricsCollectionEvent event = new MetricsCollectionEvent(UUID.randomUUID().toString(), 1L, 2L);
        when(repo.findByTaskId(any())).thenReturn(Optional.of(
                new TaskMetricsConfigRepository.TaskMetricsConfig(null)
        ));

        assertThat(builder.tryBuildFromEvent(event)).isEmpty();
    }

    @Test
    void tryBuildFromEvent_usesZeroMsWhenStartEndNull_andRequestsMissing_hits60_61_74() {
        MetricsCollectionEvent event = new MetricsCollectionEvent(UUID.randomUUID().toString(), null, null);
        when(repo.findByTaskId(any())).thenReturn(Optional.of(
                new TaskMetricsConfigRepository.TaskMetricsConfig(
                        "{\"requests\":null,\"from\":\"{testStartTimeMs}\",\"to\":\"{testEndTimeMs}\"}")
        ));

        assertThat(builder.tryBuildFromEvent(event)).isEmpty();
    }
}

