package com.loadtest.metrics.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskMetricsConfigRepositoryTest {

    private JdbcTemplate jdbc;
    private TaskMetricsConfigRepository repo;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        repo = new TaskMetricsConfigRepository(jdbc);
    }

    @Test
    void findByTaskId_andSummarizerBranches() {
        UUID id = UUID.randomUUID();

        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.ResultSetExtractor.class), any()))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    ResultSetExtractor<Optional<TaskMetricsConfigRepository.TaskMetricsConfig>> ex = inv.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.next()).thenReturn(true);
                    when(rs.getString("metrics_config")).thenReturn("{\"a\":1}");
                    return ex.extractData(rs);
                });
        assertThat(repo.findByTaskId(id)).isPresent();

        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.ResultSetExtractor.class), any()))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    ResultSetExtractor<Optional<TaskMetricsConfigRepository.TaskMetricsConfig>> ex = inv.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.next()).thenReturn(false);
                    return ex.extractData(rs);
                });
        assertThat(repo.findByTaskId(id)).isEmpty();

        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.ResultSetExtractor.class), any()))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    ResultSetExtractor<Optional<String>> ex = inv.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.next()).thenReturn(true);
                    when(rs.getString("summarizer_name")).thenReturn("route");
                    return ex.extractData(rs);
                });
        assertThat(repo.findSummarizerNameByTaskId(id)).contains("route");

        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.ResultSetExtractor.class), any()))
                .thenThrow(new RuntimeException("db"));
        assertThat(repo.findSummarizerNameByTaskId(id)).isEmpty();
        assertThat(repo.findByTaskId(id)).isEmpty();
    }

    @Test
    void findSummarizerName_fallsBackToHistoryWhenTaskBlank() {
        UUID id = UUID.randomUUID();
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.ResultSetExtractor.class), any()))
                .thenReturn(Optional.of("   "), Optional.of("hist-route"));
        assertThat(repo.findSummarizerNameByTaskId(id)).contains("hist-route");
    }

    @Test
    void findByTaskId_fallbackToHistory_withRowsAndWithoutRows() {
        UUID id = UUID.randomUUID();
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.ResultSetExtractor.class), any()))
                .thenAnswer(inv -> {
                    String sql = inv.getArgument(0);
                    @SuppressWarnings("unchecked")
                    ResultSetExtractor<Optional<TaskMetricsConfigRepository.TaskMetricsConfig>> ex = inv.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    if (sql.contains("FROM test_task WHERE")) {
                        when(rs.next()).thenReturn(false);
                    } else {
                        when(rs.next()).thenReturn(true);
                        when(rs.getString("metrics_config")).thenReturn("{\"h\":1}");
                    }
                    return ex.extractData(rs);
                });
        assertThat(repo.findByTaskId(id)).isPresent();

        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.ResultSetExtractor.class), any()))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    ResultSetExtractor<Optional<TaskMetricsConfigRepository.TaskMetricsConfig>> ex = inv.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.next()).thenReturn(false);
                    return ex.extractData(rs);
                });
        assertThat(repo.findByTaskId(id)).isEmpty();
    }

    @Test
    void findSummarizerName_bothQueriesNoRows_hitsLine48() {
        UUID id = UUID.randomUUID();
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            ResultSetExtractor<Optional<String>> ex = inv.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.next()).thenReturn(false);
            return ex.extractData(rs);
        }).when(jdbc).query(anyString(), any(org.springframework.jdbc.core.ResultSetExtractor.class), any());

        assertThat(repo.findSummarizerNameByTaskId(id)).isEmpty();
    }

    @Test
    void findSummarizerName_returnsFromTestTask_whenNonBlank_hitsIfTrueBranchLine38() {
        UUID id = UUID.randomUUID();
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.ResultSetExtractor.class), any()))
                .thenReturn(Optional.of("route-from-task"));

        assertThat(repo.findSummarizerNameByTaskId(id)).contains("route-from-task");
        verify(jdbc, times(1)).query(anyString(), any(org.springframework.jdbc.core.ResultSetExtractor.class), any());
    }

    @Test
    void findSummarizerName_fallsBackWhenTestTaskEmpty_hitsIfFalseBranchLine38() {
        UUID id = UUID.randomUUID();
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.ResultSetExtractor.class), any()))
                .thenReturn(Optional.empty(), Optional.of("route-from-history"));

        assertThat(repo.findSummarizerNameByTaskId(id)).contains("route-from-history");
        verify(jdbc, times(2)).query(anyString(), any(org.springframework.jdbc.core.ResultSetExtractor.class), any());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void findSummarizerName_fallsBackWhenFirstOptionalContainsNull_hitsMiddleBranchLine38() {
        UUID id = UUID.randomUUID();
        Optional broken = mock(Optional.class);
        when(broken.isPresent()).thenReturn(true);
        when(broken.get()).thenReturn(null);

        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.ResultSetExtractor.class), any()))
                .thenReturn(broken, Optional.of("route-from-history"));

        assertThat(repo.findSummarizerNameByTaskId(id)).contains("route-from-history");
        verify(jdbc, times(2)).query(anyString(), any(org.springframework.jdbc.core.ResultSetExtractor.class), any());
    }
}

