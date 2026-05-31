package com.loadtest.app.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadtest.app.persistence.DockerExecutionProfileRepository;
import com.loadtest.app.persistence.SummarizerModelRepository;
import com.loadtest.app.persistence.TestArtifactRepository;
import com.loadtest.app.persistence.TestMetricsRepository;
import com.loadtest.app.persistence.TestSummaryRepository;
import com.loadtest.app.persistence.TestTaskHistoryRepository;
import com.loadtest.app.persistence.TestTaskRepository;
import com.loadtest.app.service.CustomSummarizationPromptStore;
import com.loadtest.app.service.ExternalLlmDispatchService;
import com.loadtest.app.service.ExternalSummarizationCallbackService;
import com.loadtest.app.service.KafkaOutboxService;
import com.loadtest.app.service.QueuePauseService;
import com.loadtest.app.service.TestQueueService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static com.loadtest.app.testsupport.JsonTestSupport.writeValueAsString;
import static com.loadtest.app.testsupport.MockMvcTestSupport.perform;

@WebMvcTest(controllers = TasksController.class)
class TasksControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private DockerExecutionProfileRepository dockerExecutionProfileRepository;
    @MockBean
    private TestTaskRepository taskRepository;
    @MockBean
    private TestTaskHistoryRepository historyRepository;
    @MockBean
    private TestArtifactRepository artifactRepository;
    @MockBean
    private TestMetricsRepository metricsRepository;
    @MockBean
    private TestSummaryRepository summaryRepository;
    @MockBean
    private SummarizerModelRepository summarizerModelRepository;
    @MockBean
    private ExternalSummarizationCallbackService externalSummarizationCallbackService;
    @MockBean
    private ExternalLlmDispatchService externalLlmDispatchService;
    @MockBean
    private CustomSummarizationPromptStore customSummarizationPromptStore;
    @MockBean
    private KafkaOutboxService kafkaOutboxService;
    @MockBean
    private TestQueueService testQueueService;
    @MockBean
    private QueuePauseService queuePauseService;

    @Test
    void queuePause_getAndPut() {
        when(queuePauseService.getState()).thenReturn(new QueuePauseService.QueuePauseState(true, 3L));
        perform(mockMvc, get("/api/v1/loadtest/queue/pause"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paused").value(true))
                .andExpect(jsonPath("$.pendingKafkaDispatchCount").value(3));

        when(queuePauseService.setPaused(false)).thenReturn(new QueuePauseService.QueuePauseState(false, 0L));
        perform(mockMvc, put("/api/v1/loadtest/queue/pause")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(writeValueAsString(objectMapper, Map.of("paused", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paused").value(false));
    }

    @Test
    void queuePause_putMissingPaused_returns400() {
        perform(mockMvc, put("/api/v1/loadtest/queue/pause")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void queuePause_putWithStringBoolean_returns200() {
        when(queuePauseService.setPaused(true)).thenReturn(new QueuePauseService.QueuePauseState(true, 1L));
        perform(mockMvc, put("/api/v1/loadtest/queue/pause")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(writeValueAsString(objectMapper, Map.of("paused", "true"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paused").value(true));
    }

    @Test
    void deleteQueuedTask_ok() {
        UUID id = UUID.randomUUID();
        when(testQueueService.deletePendingQueueTask(eq(id))).thenReturn(TestQueueService.DeletePendingQueueTaskOutcome.DELETED);
        perform(mockMvc, delete("/api/v1/loadtest/tasks/{taskId}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"));
    }
}
