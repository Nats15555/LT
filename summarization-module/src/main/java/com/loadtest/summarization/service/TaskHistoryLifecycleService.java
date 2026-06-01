package com.loadtest.summarization.service;

import com.loadtest.summarization.persistence.TestTaskHistoryJpaRepository;
import com.loadtest.summarization.util.TaskLifecycleStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskHistoryLifecycleService {

    private final TestTaskHistoryJpaRepository historyRepository;

    @Transactional
    public void markAnalyzing(UUID taskId) {
        applyStatusUpdate(taskId, TaskLifecycleStatus.ANALYZING, null);
    }

    @Transactional
    public void markCompleted(UUID taskId) {
        applyStatusUpdate(taskId, TaskLifecycleStatus.COMPLETED, null);
    }

    @Transactional
    public void markFailed(UUID taskId, String errorMessage) {
        applyStatusUpdate(taskId, TaskLifecycleStatus.FAILED, errorMessage);
    }

    private void applyStatusUpdate(UUID taskId, String status, String errorMessage) {
        historyRepository.findById(taskId).ifPresent(history -> {
            if (TaskLifecycleStatus.isTerminal(history.getFinalStatus())) {
                return;
            }
            history.setFinalStatus(status);
            if (errorMessage != null) {
                history.setErrorMessage(errorMessage);
            }
            if (TaskLifecycleStatus.isTerminal(status)) {
                history.setFinishedAt(OffsetDateTime.now());
            }
            history.setMovedAt(OffsetDateTime.now());
            historyRepository.save(history);
            log.info("Task history {} -> {}", taskId, status);
        });
    }
}
