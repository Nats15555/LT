package com.loadtest.summarization.persistence;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@Repository
@RequiredArgsConstructor
public class TaskHistoryRepository {

    private final TestTaskHistoryJpaRepository testTaskHistoryJpaRepository;

    public Optional<String> getSummarizerNameByTaskId(UUID taskId) {
        try {
            return testTaskHistoryJpaRepository.findById(taskId)
                    .map(TestTaskHistoryEntity::getSummarizerName)
                    .filter(name -> !name.isBlank());
        } catch (RuntimeException e) {
            log.warn("Failed to load summarizer_name for taskId: {}", taskId, e);
            return Optional.empty();
        }
    }

    public boolean hasTerminalStatus(UUID taskId) {
        try {
            return testTaskHistoryJpaRepository.findById(taskId)
                    .map(TestTaskHistoryEntity::getFinalStatus)
                    .filter(com.loadtest.summarization.util.TaskLifecycleStatus::isTerminal)
                    .isPresent();
        } catch (RuntimeException e) {
            log.warn("Failed to load final_status for taskId: {}", taskId, e);
            return false;
        }
    }
}
