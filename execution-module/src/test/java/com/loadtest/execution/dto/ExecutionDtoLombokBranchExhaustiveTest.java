package com.loadtest.execution.dto;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionDtoLombokBranchExhaustiveTest {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PROFILE_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void executionRequest_recordEquals() {
        ExecutionRequest base = new ExecutionRequest("k6", "run", "/t.js", ID, 60, PROFILE_ID);
        assertThat(base).isEqualTo(new ExecutionRequest("k6", "run", "/t.js", ID, 60, PROFILE_ID));
        assertThat(base).isNotEqualTo(new ExecutionRequest("other", "run", "/t.js", ID, 60, PROFILE_ID));
        assertThat(base.testTool()).isEqualTo("k6");
        assertThat(base.command()).isEqualTo("run");
        assertThat(base.testFilePath()).isEqualTo("/t.js");
        assertThat(base.taskId()).isEqualTo(ID);
        assertThat(base.expectedDurationSeconds()).isEqualTo(60);
        assertThat(base.dockerExecutionProfileId()).isEqualTo(PROFILE_ID);
    }

    @Test
    void executionResponse_recordEquals() {
        ExecutionResponse base = new ExecutionResponse(
                "ok", "m", "cid", "cname", "art", 42L, "/rep", "/met");
        assertThat(base).isEqualTo(new ExecutionResponse(
                "ok", "m", "cid", "cname", "art", 42L, "/rep", "/met"));
        assertThat(base).isNotEqualTo(new ExecutionResponse(
                "x", "m", "cid", "cname", "art", 42L, "/rep", "/met"));
        assertThat(base.status()).isEqualTo("ok");
        assertThat(base.executionTime()).isEqualTo(42L);
    }

    @Test
    void testTaskMessage_recordEquals() {
        TestTaskMessage.MetricsConfig cfg = new TestTaskMessage.MetricsConfig(
                3, List.of(new TestTaskMessage.MetricsConfig.MetricsRequest("n", "GET", "http://u", Map.of("h", "v"), "q", "b")));
        TestTaskMessage base = new TestTaskMessage(
                "task-1", "k6", "f.js", "YQ==", "run", 30, "PENDING", 99L, cfg, PROFILE_ID.toString());
        assertThat(base).isEqualTo(new TestTaskMessage(
                "task-1", "k6", "f.js", "YQ==", "run", 30, "PENDING", 99L, cfg, PROFILE_ID.toString()));
        assertThat(base).isNotEqualTo(new TestTaskMessage(
                "task-2", "k6", "f.js", "YQ==", "run", 30, "PENDING", 99L, cfg, PROFILE_ID.toString()));
        assertThat(base.taskId()).isEqualTo("task-1");
        assertThat(base.metricsConfig()).isSameAs(cfg);
    }

    @Test
    void metricsConfig_andMetricsRequest_recordEquals() {
        List<TestTaskMessage.MetricsConfig.MetricsRequest> requests = List.of(
                new TestTaskMessage.MetricsConfig.MetricsRequest("n", "GET", "http://u", Map.of("h", "v"), "q", "b"));
        TestTaskMessage.MetricsConfig cfg = new TestTaskMessage.MetricsConfig(7, requests);
        assertThat(cfg).isEqualTo(new TestTaskMessage.MetricsConfig(7, requests));
        assertThat(cfg.delaySeconds()).isEqualTo(7);
        assertThat(cfg.requests()).isSameAs(requests);

        TestTaskMessage.MetricsConfig.MetricsRequest req =
                new TestTaskMessage.MetricsConfig.MetricsRequest("n", "GET", "http://u", Map.of("h", "v"), "q", "b");
        assertThat(req).isEqualTo(new TestTaskMessage.MetricsConfig.MetricsRequest("n", "GET", "http://u", Map.of("h", "v"), "q", "b"));
        assertThat(req).isNotEqualTo(new TestTaskMessage.MetricsConfig.MetricsRequest("x", "GET", "http://u", Map.of("h", "v"), "q", "b"));
    }

    @Test
    void metricsCollectionEvent_recordEqualsBranches() {
        MetricsCollectionEvent base = new MetricsCollectionEvent("t", 1L, 2L);
        assertThat(base).isEqualTo(new MetricsCollectionEvent("t", 1L, 2L));
        assertThat(base).isNotEqualTo(new MetricsCollectionEvent("x", 1L, 2L));
        assertThat(base.taskId()).isEqualTo("t");
        assertThat(new MetricsCollectionEvent(null, null, null))
                .isEqualTo(new MetricsCollectionEvent(null, null, null));
    }

    @Test
    void testTaskEvent_recordEqualsBranches() {
        TestTaskEvent base = new TestTaskEvent("task-1");
        assertThat(base).isEqualTo(new TestTaskEvent("task-1"));
        assertThat(base).isNotEqualTo(new TestTaskEvent("other"));
        assertThat(new TestTaskEvent(null)).isEqualTo(new TestTaskEvent(null));
    }

    @Test
    void taskProcessOutcome_record_equals() {
        ExecutionResponse resp = new ExecutionResponse("ok", "m", "cid", "cname", "art", 42L, "/rep", "/met");
        TaskProcessOutcome a = new TaskProcessOutcome(resp, 1L, 2L);
        TaskProcessOutcome b = new TaskProcessOutcome(resp, 1L, 2L);
        assertThat(a).isEqualTo(b);
        assertThat(a).isNotEqualTo(new TaskProcessOutcome(resp, 1L, 3L));
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }
}
