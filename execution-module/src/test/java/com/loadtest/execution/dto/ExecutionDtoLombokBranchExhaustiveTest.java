package com.loadtest.execution.dto;

import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionDtoLombokBranchExhaustiveTest {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PROFILE_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private static final Class<?>[] DTO_TYPES = new Class<?>[] {
            ExecutionRequest.class,
            ExecutionResponse.class,
            MetricsCollectionEvent.class,
            TestTaskEvent.class,
            TestTaskMessage.class,
            TestTaskMessage.MetricsConfig.class,
            TestTaskMessage.MetricsConfig.MetricsRequest.class
    };

    @Test
    void dto_canEqual_trueAndFalseBranches() throws Exception {
        for (Class<?> type : DTO_TYPES) {
            assertCanEqualBranches(type);
        }
    }

    @Test
    void dto_nullField_equalsBranches() throws Exception {
        for (Class<?> type : DTO_TYPES) {
            assertNullFieldBranches(type);
        }
    }

    @Test
    void dto_equalsSelf_andHashCodeNullBranches() throws Exception {
        for (Class<?> type : DTO_TYPES) {
            assertEqualsSelfAndHashCodeNullBranches(type);
        }
    }

    @Test
    void dto_equals_whenBothSidesHaveNullReferenceFields() throws Exception {
        for (Class<?> type : DTO_TYPES) {
            assertEqualsWithBothNullFieldValues(type);
        }
    }

    @Test
    void executionRequest_subclassEquals_andCanEqualFalse() {
        ExecutionRequest base = sampleExecutionRequest();
        ExecutionRequestChild child = new ExecutionRequestChild();
        BeanUtils.copyProperties(base, child);
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(base, "canEqual", child)).isTrue();
        assertThat(base).isEqualTo(child);

        ExecutionRequestCanEqualFalse other = new ExecutionRequestCanEqualFalse();
        BeanUtils.copyProperties(base, other);
        assertThat(base).isNotEqualTo(other);
    }

    @Test
    void executionRequest_allArgsConstructor_setsAllFields() {
        ExecutionRequest r = new ExecutionRequest("k6", "run", "/t.js", ID, 60, PROFILE_ID);
        assertThat(r.getTestTool()).isEqualTo("k6");
        assertThat(r.getCommand()).isEqualTo("run");
        assertThat(r.getTestFilePath()).isEqualTo("/t.js");
        assertThat(r.getTaskId()).isEqualTo(ID);
        assertThat(r.getExpectedDurationSeconds()).isEqualTo(60);
        assertThat(r.getDockerExecutionProfileId()).isEqualTo(PROFILE_ID);
    }

    @Test
    void executionRequest_explicitEqualsBranches() {
        ExecutionRequest base = sampleExecutionRequest();
        assertThat(base).isEqualTo(base);
        assertThat(base).isNotEqualTo(null);
        assertThat(base).isNotEqualTo("x");

        ExecutionRequest t = sampleExecutionRequest();
        t.setTestTool("other");
        assertThat(base).isNotEqualTo(t);
        t.setTestTool(base.getTestTool());
        t.setCommand("other");
        assertThat(base).isNotEqualTo(t);
        t.setCommand(base.getCommand());
        t.setTestFilePath("/other.js");
        assertThat(base).isNotEqualTo(t);
        t.setTestFilePath(base.getTestFilePath());
        t.setTaskId(UUID.randomUUID());
        assertThat(base).isNotEqualTo(t);
        t.setTaskId(base.getTaskId());
        t.setExpectedDurationSeconds(99);
        assertThat(base).isNotEqualTo(t);
        t.setExpectedDurationSeconds(base.getExpectedDurationSeconds());
        t.setDockerExecutionProfileId(UUID.randomUUID());
        assertThat(base).isNotEqualTo(t);

        ExecutionRequest na = new ExecutionRequest(null, null, null, null, null, null);
        ExecutionRequest nb = new ExecutionRequest(null, null, null, null, null, null);
        assertThat(na).isEqualTo(nb);
        assertThat(na.hashCode()).isEqualTo(nb.hashCode());
        na.hashCode();
    }

    @Test
    void executionResponse_allArgsConstructor_setsAllFields() {
        ExecutionResponse r = new ExecutionResponse(
                "ok", "m", "cid", "cname", "art", 42L, "/rep", "/met");
        assertThat(r.getStatus()).isEqualTo("ok");
        assertThat(r.getMessage()).isEqualTo("m");
        assertThat(r.getContainerId()).isEqualTo("cid");
        assertThat(r.getContainerName()).isEqualTo("cname");
        assertThat(r.getArtifactBaseName()).isEqualTo("art");
        assertThat(r.getExecutionTime()).isEqualTo(42L);
        assertThat(r.getReportsHostPath()).isEqualTo("/rep");
        assertThat(r.getMetricsHostPath()).isEqualTo("/met");
    }

    @Test
    void metricsCollectionEvent_allArgsConstructor_setsAllFields() {
        MetricsCollectionEvent e = new MetricsCollectionEvent("tid", 10L, 20L);
        assertThat(e.getTaskId()).isEqualTo("tid");
        assertThat(e.getTestStartTime()).isEqualTo(10L);
        assertThat(e.getTestEndTime()).isEqualTo(20L);
    }

    @Test
    void testTaskEvent_allArgsConstructor_setsTaskId() {
        TestTaskEvent ev = new TestTaskEvent("task-x");
        assertThat(ev.getTaskId()).isEqualTo("task-x");
    }

    @Test
    void testTaskMessage_allArgsConstructor_setsAllFields() {
        TestTaskMessage.MetricsConfig cfg = sampleMetricsConfig();
        TestTaskMessage m = new TestTaskMessage(
                "task-1",
                "k6",
                "f.js",
                "YQ==",
                "run",
                30,
                "PENDING",
                99L,
                cfg,
                PROFILE_ID.toString());
        assertThat(m.getTaskId()).isEqualTo("task-1");
        assertThat(m.getTestTool()).isEqualTo("k6");
        assertThat(m.getTestFileName()).isEqualTo("f.js");
        assertThat(m.getTestFileContent()).isEqualTo("YQ==");
        assertThat(m.getCommand()).isEqualTo("run");
        assertThat(m.getExpectedDurationSeconds()).isEqualTo(30);
        assertThat(m.getStatus()).isEqualTo("PENDING");
        assertThat(m.getTimestamp()).isEqualTo(99L);
        assertThat(m.getMetricsConfig()).isSameAs(cfg);
        assertThat(m.getDockerExecutionProfileId()).isEqualTo(PROFILE_ID.toString());
    }

    @Test
    void executionResponse_explicitEqualsBranches() {
        ExecutionResponse base = sampleExecutionResponse();
        assertThat(base).isEqualTo(base);
        assertThat(base).isNotEqualTo(null);
        assertThat(base).isNotEqualTo(1);

        assertThat(base).isNotEqualTo(responseLike(base).status("x").build());
        assertThat(base).isNotEqualTo(responseLike(base).message("x").build());
        assertThat(base).isNotEqualTo(responseLike(base).containerId("x").build());
        assertThat(base).isNotEqualTo(responseLike(base).containerName("x").build());
        assertThat(base).isNotEqualTo(responseLike(base).artifactBaseName("x").build());
        assertThat(base).isNotEqualTo(responseLike(base).executionTime(0L).build());
        assertThat(base).isNotEqualTo(responseLike(base).reportsHostPath("/x").build());
        assertThat(base).isNotEqualTo(responseLike(base).metricsHostPath("/x").build());

        ExecutionResponse na = ExecutionResponse.builder().build();
        ExecutionResponse nb = ExecutionResponse.builder().build();
        assertThat(na).isEqualTo(nb);
        assertThat(na.hashCode()).isEqualTo(nb.hashCode());
        na.hashCode();
    }

    @Test
    void testTaskMessage_explicitEqualsBranches() {
        TestTaskMessage base = sampleTestTaskMessage();
        assertThat(base).isEqualTo(base);
        assertThat(base).isNotEqualTo(null);
        assertThat(base).isNotEqualTo(1);

        assertThat(base).isNotEqualTo(messageLike(base).taskId("x").build());
        assertThat(base).isNotEqualTo(messageLike(base).testTool("x").build());
        assertThat(base).isNotEqualTo(messageLike(base).testFileName("x").build());
        assertThat(base).isNotEqualTo(messageLike(base).testFileContent("x").build());
        assertThat(base).isNotEqualTo(messageLike(base).command("x").build());
        assertThat(base).isNotEqualTo(messageLike(base).expectedDurationSeconds(0).build());
        assertThat(base).isNotEqualTo(messageLike(base).status("x").build());
        assertThat(base).isNotEqualTo(messageLike(base).timestamp(0L).build());
        assertThat(base).isNotEqualTo(messageLike(base).metricsConfig(null).build());
        assertThat(base).isNotEqualTo(messageLike(base).dockerExecutionProfileId("x").build());

        TestTaskMessage na = TestTaskMessage.builder().build();
        TestTaskMessage nb = TestTaskMessage.builder().build();
        assertThat(na).isEqualTo(nb);
        assertThat(na.hashCode()).isEqualTo(nb.hashCode());
        na.hashCode();
    }

    @Test
    void executionResponse_subclassEquals_andCanEqualFalse() {
        ExecutionResponse base = sampleExecutionResponse();
        ExecutionResponseChild child = new ExecutionResponseChild();
        BeanUtils.copyProperties(base, child);
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(base, "canEqual", child)).isTrue();
        assertThat(base).isEqualTo(child);

        ExecutionResponseCanEqualFalse other = new ExecutionResponseCanEqualFalse();
        BeanUtils.copyProperties(base, other);
        assertThat(base).isNotEqualTo(other);
    }

    @Test
    void metricsCollectionEvent_subclassEquals_andCanEqualFalse() {
        MetricsCollectionEvent base = sampleMetricsCollectionEvent();
        MetricsCollectionEventChild child = new MetricsCollectionEventChild();
        BeanUtils.copyProperties(base, child);
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(base, "canEqual", child)).isTrue();
        assertThat(base).isEqualTo(child);

        MetricsCollectionEventCanEqualFalse other = new MetricsCollectionEventCanEqualFalse();
        BeanUtils.copyProperties(base, other);
        assertThat(base).isNotEqualTo(other);
    }

    @Test
    void testTaskEvent_subclassEquals_andCanEqualFalse() {
        TestTaskEvent base = sampleTestTaskEvent();
        TestTaskEventChild child = new TestTaskEventChild();
        BeanUtils.copyProperties(base, child);
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(base, "canEqual", child)).isTrue();
        assertThat(base).isEqualTo(child);

        TestTaskEventCanEqualFalse other = new TestTaskEventCanEqualFalse();
        BeanUtils.copyProperties(base, other);
        assertThat(base).isNotEqualTo(other);
    }

    @Test
    void testTaskEvent_explicitEqualsBranches() {
        TestTaskEvent base = sampleTestTaskEvent();
        assertThat(base).isEqualTo(base);
        assertThat(base).isNotEqualTo(null);
        assertThat(base).isNotEqualTo("x");
        assertThat(base).isNotEqualTo(TestTaskEvent.builder().taskId("other").build());
        TestTaskEvent na = TestTaskEvent.builder().taskId(null).build();
        TestTaskEvent nb = TestTaskEvent.builder().taskId(null).build();
        assertThat(na).isEqualTo(nb);
        assertThat(na.hashCode()).isEqualTo(nb.hashCode());
        na.hashCode();
    }

    @Test
    void testTaskMessage_subclassEquals_andCanEqualFalse() {
        TestTaskMessage base = sampleTestTaskMessage();
        TestTaskMessageChild child = new TestTaskMessageChild();
        BeanUtils.copyProperties(base, child);
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(base, "canEqual", child)).isTrue();
        assertThat(base).isEqualTo(child);

        TestTaskMessageCanEqualFalse other = new TestTaskMessageCanEqualFalse();
        BeanUtils.copyProperties(base, other);
        assertThat(base).isNotEqualTo(other);
    }

    @Test
    void metricsConfig_subclassEquals_andCanEqualFalse() {
        TestTaskMessage.MetricsConfig base = sampleMetricsConfig();
        MetricsConfigChild child = new MetricsConfigChild();
        BeanUtils.copyProperties(base, child);
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(base, "canEqual", child)).isTrue();
        assertThat(base).isEqualTo(child);

        MetricsConfigCanEqualFalse other = new MetricsConfigCanEqualFalse();
        BeanUtils.copyProperties(base, other);
        assertThat(base).isNotEqualTo(other);
    }

    @Test
    void metricsConfig_allArgsConstructor_setsDelaySecondsAndRequests() {
        List<TestTaskMessage.MetricsConfig.MetricsRequest> requests = List.of(sampleMetricsRequest());
        TestTaskMessage.MetricsConfig cfg = new TestTaskMessage.MetricsConfig(7, requests);
        assertThat(cfg.getDelaySeconds()).isEqualTo(7);
        assertThat(cfg.getRequests()).isSameAs(requests);
    }

    @Test
    void metricsRequest_subclassEquals_andCanEqualFalse() {
        TestTaskMessage.MetricsConfig.MetricsRequest base = sampleMetricsRequest();
        MetricsRequestChild child = new MetricsRequestChild();
        BeanUtils.copyProperties(base, child);
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(base, "canEqual", child)).isTrue();
        assertThat(base).isEqualTo(child);

        MetricsRequestCanEqualFalse other = new MetricsRequestCanEqualFalse();
        BeanUtils.copyProperties(base, other);
        assertThat(base).isNotEqualTo(other);
    }

    @Test
    void metricsRequest_explicitEqualsBranches() {
        TestTaskMessage.MetricsConfig.MetricsRequest base = sampleMetricsRequest();
        assertThat(base).isEqualTo(base);
        assertThat(base).isNotEqualTo(null);
        assertThat(base).isNotEqualTo("x");
        assertThat(base).isNotEqualTo(new TestTaskMessage.MetricsConfig.MetricsRequest(
                "o", "GET", "http://u", Map.of("h", "v"), "q", "b"));
        assertThat(base).isNotEqualTo(new TestTaskMessage.MetricsConfig.MetricsRequest(
                "n", "POST", "http://u", Map.of("h", "v"), "q", "b"));
        assertThat(base).isNotEqualTo(new TestTaskMessage.MetricsConfig.MetricsRequest(
                "n", "GET", "http://other", Map.of("h", "v"), "q", "b"));
        assertThat(base).isNotEqualTo(new TestTaskMessage.MetricsConfig.MetricsRequest(
                "n", "GET", "http://u", Map.of("x", "y"), "q", "b"));
        assertThat(base).isNotEqualTo(new TestTaskMessage.MetricsConfig.MetricsRequest(
                "n", "GET", "http://u", Map.of("h", "v"), "q2", "b"));
        assertThat(base).isNotEqualTo(new TestTaskMessage.MetricsConfig.MetricsRequest(
                "n", "GET", "http://u", Map.of("h", "v"), "q", "b2"));

        TestTaskMessage.MetricsConfig.MetricsRequest na =
                new TestTaskMessage.MetricsConfig.MetricsRequest(null, null, null, null, null, null);
        TestTaskMessage.MetricsConfig.MetricsRequest nb =
                new TestTaskMessage.MetricsConfig.MetricsRequest(null, null, null, null, null, null);
        assertThat(na).isEqualTo(nb);
        assertThat(na.hashCode()).isEqualTo(nb.hashCode());
        na.hashCode();
    }

    @Test
    void metricsCollectionEvent_explicitEqualsBranches() {
        MetricsCollectionEvent base = sampleMetricsCollectionEvent();
        assertThat(base).isEqualTo(base);
        assertThat(base).isNotEqualTo(null);
        assertThat(base).isNotEqualTo(MetricsCollectionEvent.builder().taskId("x").testStartTime(1L).testEndTime(2L).build());
        assertThat(base).isNotEqualTo(MetricsCollectionEvent.builder().taskId("t").testStartTime(9L).testEndTime(2L).build());
        assertThat(base).isNotEqualTo(MetricsCollectionEvent.builder().taskId("t").testStartTime(1L).testEndTime(9L).build());

        MetricsCollectionEvent na = MetricsCollectionEvent.builder().taskId(null).testStartTime(null).testEndTime(null).build();
        MetricsCollectionEvent nb = MetricsCollectionEvent.builder().taskId(null).testStartTime(null).testEndTime(null).build();
        assertThat(na).isEqualTo(nb);
        assertThat(na.hashCode()).isEqualTo(nb.hashCode());
        na.hashCode();
    }

    @Test
    void taskProcessOutcome_record_equals() {
        ExecutionResponse resp = sampleExecutionResponse();
        TaskProcessOutcome a = new TaskProcessOutcome(resp, 1L, 2L);
        TaskProcessOutcome b = new TaskProcessOutcome(resp, 1L, 2L);
        assertThat(a).isEqualTo(b);
        assertThat(a).isNotEqualTo(new TaskProcessOutcome(resp, 1L, 3L));
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    private static ExecutionResponse.ExecutionResponseBuilder responseLike(ExecutionResponse b) {
        return ExecutionResponse.builder()
                .status(b.getStatus())
                .message(b.getMessage())
                .containerId(b.getContainerId())
                .containerName(b.getContainerName())
                .artifactBaseName(b.getArtifactBaseName())
                .executionTime(b.getExecutionTime())
                .reportsHostPath(b.getReportsHostPath())
                .metricsHostPath(b.getMetricsHostPath());
    }

    private static TestTaskMessage.TestTaskMessageBuilder messageLike(TestTaskMessage m) {
        return TestTaskMessage.builder()
                .taskId(m.getTaskId())
                .testTool(m.getTestTool())
                .testFileName(m.getTestFileName())
                .testFileContent(m.getTestFileContent())
                .command(m.getCommand())
                .expectedDurationSeconds(m.getExpectedDurationSeconds())
                .status(m.getStatus())
                .timestamp(m.getTimestamp())
                .metricsConfig(m.getMetricsConfig())
                .dockerExecutionProfileId(m.getDockerExecutionProfileId());
    }

    private static ExecutionRequest sampleExecutionRequest() {
        ExecutionRequest r = new ExecutionRequest();
        r.setTestTool("k6");
        r.setCommand("run");
        r.setTestFilePath("/t.js");
        r.setTaskId(ID);
        r.setExpectedDurationSeconds(60);
        r.setDockerExecutionProfileId(PROFILE_ID);
        return r;
    }

    private static ExecutionResponse sampleExecutionResponse() {
        return ExecutionResponse.builder()
                .status("ok")
                .message("m")
                .containerId("cid")
                .containerName("cname")
                .artifactBaseName("art")
                .executionTime(42L)
                .reportsHostPath("/rep")
                .metricsHostPath("/met")
                .build();
    }

    private static MetricsCollectionEvent sampleMetricsCollectionEvent() {
        return MetricsCollectionEvent.builder()
                .taskId("t")
                .testStartTime(1L)
                .testEndTime(2L)
                .build();
    }

    private static TestTaskEvent sampleTestTaskEvent() {
        return TestTaskEvent.builder().taskId("task-1").build();
    }

    private static TestTaskMessage sampleTestTaskMessage() {
        return TestTaskMessage.builder()
                .taskId("task-1")
                .testTool("k6")
                .testFileName("f.js")
                .testFileContent("YQ==")
                .command("run")
                .expectedDurationSeconds(30)
                .status("PENDING")
                .timestamp(99L)
                .metricsConfig(sampleMetricsConfig())
                .dockerExecutionProfileId(PROFILE_ID.toString())
                .build();
    }

    private static TestTaskMessage.MetricsConfig sampleMetricsConfig() {
        TestTaskMessage.MetricsConfig c = new TestTaskMessage.MetricsConfig();
        c.setDelaySeconds(3);
        c.setRequests(List.of(sampleMetricsRequest()));
        return c;
    }

    private static TestTaskMessage.MetricsConfig.MetricsRequest sampleMetricsRequest() {
        return new TestTaskMessage.MetricsConfig.MetricsRequest(
                "n", "GET", "http://u", Map.of("h", "v"), "q", "b");
    }

    private static void assertCanEqualBranches(Class<?> type) throws Exception {
        Object left = newInstance(type);
        Object right = newInstance(type);
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(left, "canEqual", right)).isTrue();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(left, "canEqual", "x")).isFalse();
    }

    private static void assertNullFieldBranches(Class<?> type) throws Exception {
        Object base = newInstance(type);
        setAllFields(base, 1);
        for (Field f : instanceFields(type)) {
            if (f.getType().isPrimitive()) {
                continue;
            }
            Object leftNull = cloneWithAllFields(type, base);
            Object rightValue = cloneWithAllFields(type, base);
            f.set(leftNull, null);
            assertThat(leftNull).isNotEqualTo(rightValue);

            Object leftValue = cloneWithAllFields(type, base);
            Object rightNull = cloneWithAllFields(type, base);
            f.set(rightNull, null);
            assertThat(leftValue).isNotEqualTo(rightNull);
        }
    }

    private static void assertEqualsSelfAndHashCodeNullBranches(Class<?> type) throws Exception {
        Object base = newInstance(type);
        setAllFields(base, 1);
        assertThat(base).isEqualTo(base);
        base.hashCode();
        for (Field f : instanceFields(type)) {
            if (f.getType().isPrimitive()) {
                continue;
            }
            Object withNull = cloneWithAllFields(type, base);
            f.set(withNull, null);
            withNull.hashCode();
        }
    }

    private static void assertEqualsWithBothNullFieldValues(Class<?> type) throws Exception {
        Object left = newInstance(type);
        Object right = newInstance(type);
        setAllFields(left, 1);
        setAllFields(right, 1);
        for (Field f : instanceFields(type)) {
            if (!f.getType().isPrimitive()) {
                f.set(left, null);
                f.set(right, null);
            }
        }
        assertThat(left).isEqualTo(right);
        assertThat(left.hashCode()).isEqualTo(right.hashCode());
    }

    private static Object newInstance(Class<?> type) throws Exception {
        try {
            var ctor = type.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (NoSuchMethodException e) {
            List<Field> fs = instanceFields(type);
            Class<?>[] sig = fs.stream().map(Field::getType).toArray(Class[]::new);
            Object[] args = fs.stream().map(f -> valueForField(f.getType(), f.getName(), 1)).toArray();
            var ctor = type.getDeclaredConstructor(sig);
            ctor.setAccessible(true);
            return ctor.newInstance(args);
        }
    }

    private static List<Field> instanceFields(Class<?> type) {
        List<Field> out = new ArrayList<>();
        for (Field f : type.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) && !f.isSynthetic()) {
                f.setAccessible(true);
                out.add(f);
            }
        }
        return out;
    }

    private static void setAllFields(Object target, int seed) throws Exception {
        for (Field f : instanceFields(target.getClass())) {
            f.set(target, valueForField(f.getType(), f.getName(), seed));
        }
    }

    private static Object cloneWithAllFields(Class<?> type, Object source) throws Exception {
        Object copy = newInstance(type);
        for (Field f : instanceFields(type)) {
            f.set(copy, f.get(source));
        }
        return copy;
    }

    private static Object valueForField(Class<?> t, String name, int seed) {
        if (t == String.class) return name + "-" + seed;
        if (t == Integer.class || t == int.class) return 10 + seed;
        if (t == Long.class || t == long.class) return 100L + seed;
        if (t == BigDecimal.class) return BigDecimal.valueOf(10 + seed);
        if (t == Double.class || t == double.class) return 1.0 + seed;
        if (t == Float.class || t == float.class) return 1.0f + seed;
        if (t == Short.class || t == short.class) return (short) (10 + seed);
        if (t == Byte.class || t == byte.class) return (byte) (10 + seed);
        if (t == Boolean.class || t == boolean.class) return seed % 2 == 0;
        if (t == UUID.class) return UUID.nameUUIDFromBytes((name + "-" + seed).getBytes());
        if (t == OffsetDateTime.class) {
            return OffsetDateTime.parse(seed == 1 ? "2024-01-01T00:00:00Z" : "2024-01-02T00:00:00Z");
        }
        if (t == byte[].class) return seed == 1 ? new byte[] {1} : new byte[] {2};
        if (List.class.isAssignableFrom(t)) {
            var mr = new TestTaskMessage.MetricsConfig.MetricsRequest(
                    "vf-" + name + "-" + seed,
                    "GET",
                    "http://u/" + seed,
                    null,
                    null,
                    null);
            return List.of(mr);
        }
        if (Map.class.isAssignableFrom(t)) {
            return seed == 1 ? Map.of("k", "v") : Map.of("k2", "v2");
        }
        if (t.isEnum()) {
            Object[] c = t.getEnumConstants();
            return c[(seed - 1) % c.length];
        }
        try {
            var ctor = t.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (Exception ignored) {
            return seed == 1 ? new LinkedHashMap<>() : new ArrayList<>();
        }
    }

    private static final class ExecutionRequestChild extends ExecutionRequest {}

    private static final class ExecutionRequestCanEqualFalse extends ExecutionRequest {
        @Override
        protected boolean canEqual(Object other) {
            return false;
        }
    }

    private static final class ExecutionResponseChild extends ExecutionResponse {}

    private static final class ExecutionResponseCanEqualFalse extends ExecutionResponse {
        @Override
        protected boolean canEqual(Object other) {
            return false;
        }
    }

    private static final class MetricsCollectionEventChild extends MetricsCollectionEvent {}

    private static final class MetricsCollectionEventCanEqualFalse extends MetricsCollectionEvent {
        @Override
        protected boolean canEqual(Object other) {
            return false;
        }
    }

    private static final class TestTaskEventChild extends TestTaskEvent {}

    private static final class TestTaskEventCanEqualFalse extends TestTaskEvent {
        @Override
        protected boolean canEqual(Object other) {
            return false;
        }
    }

    private static final class TestTaskMessageChild extends TestTaskMessage {}

    private static final class TestTaskMessageCanEqualFalse extends TestTaskMessage {
        @Override
        protected boolean canEqual(Object other) {
            return false;
        }
    }

    private static final class MetricsConfigChild extends TestTaskMessage.MetricsConfig {}

    private static final class MetricsConfigCanEqualFalse extends TestTaskMessage.MetricsConfig {
        @Override
        protected boolean canEqual(Object other) {
            return false;
        }
    }

    private static final class MetricsRequestChild extends TestTaskMessage.MetricsConfig.MetricsRequest {}

    private static final class MetricsRequestCanEqualFalse extends TestTaskMessage.MetricsConfig.MetricsRequest {
        @Override
        protected boolean canEqual(Object other) {
            return false;
        }
    }
}
