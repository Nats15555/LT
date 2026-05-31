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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskMetricsConfigRepositoryTest {

    @Mock
    private TestTaskJpaRepository testTaskJpaRepository;
    @Mock
    private TestTaskHistoryJpaRepository testTaskHistoryJpaRepository;

    private TaskMetricsConfigRepository repo;

    @BeforeEach
    void setUp() {
        repo = new TaskMetricsConfigRepository(testTaskJpaRepository, testTaskHistoryJpaRepository);
    }

    @Test
    void findByTaskId_andSummarizerBranches() {
        UUID id = UUID.randomUUID();
        when(testTaskJpaRepository.findById(id)).thenReturn(Optional.of(taskWithConfig()));
        assertThat(repo.findByTaskId(id)).isPresent();

        when(testTaskJpaRepository.findById(id)).thenReturn(Optional.empty());
        when(testTaskHistoryJpaRepository.findById(id)).thenReturn(Optional.empty());
        assertThat(repo.findByTaskId(id)).isEmpty();

        when(testTaskJpaRepository.findById(id)).thenReturn(Optional.of(taskWithSummarizer("route")));
        assertThat(repo.findSummarizerNameByTaskId(id)).contains("route");

        when(testTaskJpaRepository.findById(id)).thenThrow(new RuntimeException("db"));
        assertThat(repo.findSummarizerNameByTaskId(id)).isEmpty();
        assertThat(repo.findByTaskId(id)).isEmpty();
    }

    @Test
    void findSummarizerName_fallsBackToHistoryWhenTaskBlank() {
        UUID id = UUID.randomUUID();
        when(testTaskJpaRepository.findById(id)).thenReturn(Optional.of(taskWithSummarizer("   ")));
        when(testTaskHistoryJpaRepository.findById(id)).thenReturn(Optional.of(historyWithSummarizer("hist-route")));
        assertThat(repo.findSummarizerNameByTaskId(id)).contains("hist-route");
    }

    @Test
    void findByTaskId_fallbackToHistory_withRowsAndWithoutRows() {
        UUID id = UUID.randomUUID();
        when(testTaskJpaRepository.findById(id)).thenReturn(Optional.empty());
        when(testTaskHistoryJpaRepository.findById(id)).thenReturn(Optional.of(historyWithConfig()));
        assertThat(repo.findByTaskId(id)).isPresent();

        when(testTaskJpaRepository.findById(id)).thenReturn(Optional.empty());
        when(testTaskHistoryJpaRepository.findById(id)).thenReturn(Optional.empty());
        assertThat(repo.findByTaskId(id)).isEmpty();
    }

    @Test
    void findSummarizerName_bothQueriesNoRows() {
        UUID id = UUID.randomUUID();
        when(testTaskJpaRepository.findById(id)).thenReturn(Optional.empty());
        when(testTaskHistoryJpaRepository.findById(id)).thenReturn(Optional.empty());
        assertThat(repo.findSummarizerNameByTaskId(id)).isEmpty();
    }

    @Test
    void findSummarizerName_returnsFromTestTask_whenNonBlank() {
        UUID id = UUID.randomUUID();
        when(testTaskJpaRepository.findById(id)).thenReturn(Optional.of(taskWithSummarizer("route-from-task")));
        assertThat(repo.findSummarizerNameByTaskId(id)).contains("route-from-task");
        verify(testTaskHistoryJpaRepository, times(0)).findById(id);
    }

    @Test
    void findSummarizerName_fallsBackWhenTestTaskEmpty() {
        UUID id = UUID.randomUUID();
        when(testTaskJpaRepository.findById(id)).thenReturn(Optional.empty());
        when(testTaskHistoryJpaRepository.findById(id)).thenReturn(Optional.of(historyWithSummarizer("route-from-history")));
        assertThat(repo.findSummarizerNameByTaskId(id)).contains("route-from-history");
        verify(testTaskHistoryJpaRepository).findById(id);
    }

    @Test
    void findSummarizerName_fallsBackWhenTaskSummarizerNull() {
        UUID id = UUID.randomUUID();
        when(testTaskJpaRepository.findById(id)).thenReturn(Optional.of(taskWithSummarizer(null)));
        when(testTaskHistoryJpaRepository.findById(id)).thenReturn(Optional.of(historyWithSummarizer("route-from-history")));
        assertThat(repo.findSummarizerNameByTaskId(id)).contains("route-from-history");
    }

    private static TestTaskEntity taskWithConfig() {
        return TestTaskEntity.builder()
                .id(UUID.randomUUID())
                .status(TestTaskStatus.PENDING)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .testTool("k6")
                .testFileName("f.js")
                .testFileContentBase64("YQ==")
                .command("run")
                .metricsConfig("{\"a\":1}")
                .dockerExecutionProfileId(UUID.randomUUID())
                .build();
    }

    private static TestTaskEntity taskWithSummarizer(String summarizerName) {
        return TestTaskEntity.builder()
                .id(UUID.randomUUID())
                .status(TestTaskStatus.PENDING)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .testTool("k6")
                .testFileName("f.js")
                .testFileContentBase64("YQ==")
                .command("run")
                .summarizerName(summarizerName)
                .dockerExecutionProfileId(UUID.randomUUID())
                .build();
    }

    private static TestTaskHistoryEntity historyWithConfig() {
        OffsetDateTime now = OffsetDateTime.now();
        return TestTaskHistoryEntity.builder()
                .id(UUID.randomUUID())
                .finalStatus("OK")
                .createdAt(now)
                .movedAt(now)
                .testTool("k6")
                .testFileName("f.js")
                .testFileContentBase64("YQ==")
                .command("run")
                .metricsConfig("{\"h\":1}")
                .build();
    }

    private static TestTaskHistoryEntity historyWithSummarizer(String summarizerName) {
        OffsetDateTime now = OffsetDateTime.now();
        return TestTaskHistoryEntity.builder()
                .id(UUID.randomUUID())
                .finalStatus("OK")
                .createdAt(now)
                .movedAt(now)
                .testTool("k6")
                .testFileName("f.js")
                .testFileContentBase64("YQ==")
                .command("run")
                .summarizerName(summarizerName)
                .build();
    }
}
