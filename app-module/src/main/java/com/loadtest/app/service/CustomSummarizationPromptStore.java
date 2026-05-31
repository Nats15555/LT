package com.loadtest.app.service;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class CustomSummarizationPromptStore {

    private final ConcurrentHashMap<UUID, String> byTaskId = new ConcurrentHashMap<>();

    public void put(UUID taskId, String customPrompt) {
        if (taskId == null || customPrompt == null || customPrompt.isBlank()) {
            return;
        }
        byTaskId.put(taskId, customPrompt.trim());
    }

    public Optional<String> consume(UUID taskId) {
        if (taskId == null) {
            return Optional.empty();
        }
        String removed = byTaskId.remove(taskId);
        if (removed == null || removed.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(removed);
    }
}
