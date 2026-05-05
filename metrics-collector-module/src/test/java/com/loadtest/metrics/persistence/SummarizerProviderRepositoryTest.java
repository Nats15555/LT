package com.loadtest.metrics.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SummarizerProviderRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private SummarizerProviderRepository repository;

    @BeforeEach
    void setUp() {
        repository = new SummarizerProviderRepository(jdbcTemplate);
    }

    @Test
    void findProvider_branches() {
        assertThat(repository.findProviderBySummarizerName(null)).isEmpty();
        assertThat(repository.findProviderBySummarizerName("  ")).isEmpty();

        when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.ResultSetExtractor.class), any()))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    ResultSetExtractor<Optional<String>> ex = inv.getArgument(1);
                    ResultSet rs = org.mockito.Mockito.mock(ResultSet.class);
                    when(rs.next()).thenReturn(true);
                    when(rs.getString("provider")).thenReturn("EXTERNAL");
                    return ex.extractData(rs);
                });
        assertThat(repository.findProviderBySummarizerName("route")).contains("EXTERNAL");

        when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.ResultSetExtractor.class), any()))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    ResultSetExtractor<Optional<String>> ex = inv.getArgument(1);
                    ResultSet rs = org.mockito.Mockito.mock(ResultSet.class);
                    when(rs.next()).thenReturn(false);
                    return ex.extractData(rs);
                });
        assertThat(repository.findProviderBySummarizerName("missing")).isEmpty();

        when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.ResultSetExtractor.class), any()))
                .thenThrow(new RuntimeException("db"));
        assertThat(repository.findProviderBySummarizerName("route")).isEmpty();
    }

    @Test
    void isEnabled_branches() {
        assertThat(repository.isSummarizerEnabled(null)).isFalse();
        assertThat(repository.isSummarizerEnabled(" ")).isFalse();

        when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.ResultSetExtractor.class), any()))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    ResultSetExtractor<Boolean> ex = inv.getArgument(1);
                    ResultSet rs = org.mockito.Mockito.mock(ResultSet.class);
                    when(rs.next()).thenReturn(true);
                    when(rs.getBoolean("enabled")).thenReturn(true);
                    return ex.extractData(rs);
                });
        assertThat(repository.isSummarizerEnabled("route")).isTrue();

        when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.ResultSetExtractor.class), any()))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    ResultSetExtractor<Boolean> ex = inv.getArgument(1);
                    ResultSet rs = org.mockito.Mockito.mock(ResultSet.class);
                    when(rs.next()).thenReturn(true);
                    when(rs.getBoolean("enabled")).thenReturn(false);
                    return ex.extractData(rs);
                });
        assertThat(repository.isSummarizerEnabled("route")).isFalse();

        when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.ResultSetExtractor.class), any()))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    ResultSetExtractor<Boolean> ex = inv.getArgument(1);
                    ResultSet rs = org.mockito.Mockito.mock(ResultSet.class);
                    when(rs.next()).thenReturn(false);
                    return ex.extractData(rs);
                });
        assertThat(repository.isSummarizerEnabled("missing")).isFalse();

        when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.ResultSetExtractor.class), any()))
                .thenThrow(new RuntimeException("db"));
        assertThat(repository.isSummarizerEnabled("route")).isFalse();
    }
}

