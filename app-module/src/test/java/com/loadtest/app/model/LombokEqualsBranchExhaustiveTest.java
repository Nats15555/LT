package com.loadtest.app.model;

import com.loadtest.app.dto.ArtifactInfoDto;
import com.loadtest.app.dto.LoadTestToolDto;
import com.loadtest.app.dto.SummarizationTaskEvent;
import com.loadtest.app.dto.SummarizerModelDto;
import com.loadtest.app.dto.TaskQueueItemDto;
import com.loadtest.app.dto.TestTaskEvent;
import com.loadtest.app.dto.TestTaskMessage;
import com.loadtest.app.persistence.DockerExecutionProfileEntity;
import com.loadtest.app.persistence.LoadTestToolEntity;
import com.loadtest.app.persistence.SummarizerModelEntity;
import com.loadtest.app.persistence.TestArtifactEntity;
import com.loadtest.app.persistence.TestMetricsEntity;
import com.loadtest.app.persistence.TestSummaryEntity;
import com.loadtest.app.persistence.TestTaskEntity;
import com.loadtest.app.persistence.TestTaskHistoryEntity;
import com.loadtest.app.persistence.TestTaskStatus;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.loadtest.app.testsupport.ReflectionTestSupport.getField;
import static com.loadtest.app.testsupport.ReflectionTestSupport.newInstance;
import static com.loadtest.app.testsupport.ReflectionTestSupport.setField;
import static org.assertj.core.api.Assertions.assertThat;

class LombokEqualsBranchExhaustiveTest {

    private static final Class<?>[] ENTITY_CLASSES = new Class<?>[] {
            DockerExecutionProfileEntity.class,
            LoadTestToolEntity.class,
            SummarizerModelEntity.class,
            TestArtifactEntity.class,
            TestMetricsEntity.class,
            TestSummaryEntity.class,
            TestTaskEntity.class,
            TestTaskHistoryEntity.class
    };

    @Test
    void kafkaEventRecords_equalsBranches() {
        TestTaskEvent taskEvent = new TestTaskEvent("t1");
        assertThat(taskEvent).isEqualTo(new TestTaskEvent("t1"));
        assertThat(taskEvent).isNotEqualTo(new TestTaskEvent("t2"));

        SummarizationTaskEvent sumEvent = new SummarizationTaskEvent("t1", "sum");
        assertThat(sumEvent).isEqualTo(new SummarizationTaskEvent("t1", "sum"));
        assertThat(sumEvent).isNotEqualTo(new SummarizationTaskEvent("t2", "sum"));
    }

    @Test
    void recordDto_equalsSmokeTests() {
        UUID id = UUID.randomUUID();
        OffsetDateTime ts = OffsetDateTime.parse("2024-01-01T00:00:00Z");

        ArtifactInfoDto artifact = new ArtifactInfoDto(id, "f.txt", 42L);
        assertThat(artifact).isEqualTo(new ArtifactInfoDto(id, "f.txt", 42L));
        assertThat(artifact).isNotEqualTo(new ArtifactInfoDto(id, "other.txt", 42L));

        LoadTestToolDto tool = new LoadTestToolDto(id, "K6", "k6", List.of(".js"), true, ts, ts);
        assertThat(tool).isEqualTo(new LoadTestToolDto(id, "K6", "k6", List.of(".js"), true, ts, ts));
        assertThat(tool).isNotEqualTo(new LoadTestToolDto(id, "JMETER", "k6", List.of(".js"), true, ts, ts));

        SummarizerModelDto summarizer = new SummarizerModelDto(id, "s", "OPENAI", null, "m", null, true, ts, ts);
        assertThat(summarizer).isEqualTo(new SummarizerModelDto(id, "s", "OPENAI", null, "m", null, true, ts, ts));

        TaskQueueItemDto queueItem = new TaskQueueItemDto(id, "PENDING", "K6", "f.js", null, id, "prof", ts);
        assertThat(queueItem).isEqualTo(new TaskQueueItemDto(id, "PENDING", "K6", "f.js", null, id, "prof", ts));

        TestTaskMessage.MetricsConfig cfg = new TestTaskMessage.MetricsConfig(
                1, List.of(new TestTaskMessage.MetricsConfig.MetricsRequest("n", "GET", "http://u", null, null, null)));
        TestTaskMessage message = new TestTaskMessage("t1", "K6", "f.js", "YQ==", null, null, cfg);
        assertThat(message).isEqualTo(new TestTaskMessage("t1", "K6", "f.js", "YQ==", null, null, cfg));
        assertThat(message).isNotEqualTo(new TestTaskMessage("t2", "K6", "f.js", "YQ==", null, null, cfg));
    }

    @Test
    void equalsHashCode_branchesCoveredForEntityFields() {
        for (Class<?> type : ENTITY_CLASSES) {
            assertEqualsBranchesPerField(type);
        }
        assertThat(TestTaskStatus.values()).contains(TestTaskStatus.PENDING, TestTaskStatus.PROCESSING);
        assertThat(TestTaskStatus.valueOf("PENDING")).isEqualTo(TestTaskStatus.PENDING);
    }

    private static void assertEqualsBranchesPerField(Class<?> type) {
        Object base = newInstance(type);
        setAllFields(base);
        Object same = cloneWithAllFields(type, base);

        assertThat(base).isEqualTo(same);
        assertThat(base.hashCode()).isEqualTo(same.hashCode());
        assertThat(base).isNotEqualTo(null);
        assertThat(base).isNotEqualTo("other");
        assertThat(base.toString()).isNotBlank();

        List<Field> fields = instanceFields(type);
        for (Field f : fields) {
            Object diff = cloneWithAllFields(type, base);
            setField(f, diff, valueForField(f.getType(), f.getName(), 2));
            assertThat(base).as(type.getSimpleName() + "." + f.getName()).isNotEqualTo(diff);

            if (!f.getType().isPrimitive()) {
                Object leftNull = cloneWithAllFields(type, base);
                Object rightNull = cloneWithAllFields(type, base);
                setField(f, leftNull, null);
                setField(f, rightNull, null);
                assertThat(leftNull).as(type.getSimpleName() + "." + f.getName() + " both null").isEqualTo(rightNull);

                Object rightNonNull = cloneWithAllFields(type, base);
                setField(f, rightNonNull, valueForField(f.getType(), f.getName(), 3));
                assertThat(leftNull).as(type.getSimpleName() + "." + f.getName() + " null vs non-null").isNotEqualTo(rightNonNull);

                Object leftNonNull = cloneWithAllFields(type, base);
                Object rightNullFromNonNull = cloneWithAllFields(type, base);
                setField(f, rightNullFromNonNull, null);
                assertThat(leftNonNull)
                        .as(type.getSimpleName() + "." + f.getName() + " non-null vs null")
                        .isNotEqualTo(rightNullFromNonNull);
            }
        }
    }

    private static List<Field> instanceFields(Class<?> type) {
        List<Field> out = new ArrayList<>();
        for (Field f : type.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            f.setAccessible(true);
            out.add(f);
        }
        return out;
    }

    private static void setAllFields(Object target) {
        for (Field f : instanceFields(target.getClass())) {
            setField(f, target, valueForField(f.getType(), f.getName(), 1));
        }
    }

    private static Object cloneWithAllFields(Class<?> type, Object source) {
        Object copy = newInstance(type);
        for (Field f : instanceFields(type)) {
            setField(f, copy, getField(f, source));
        }
        return copy;
    }

    private static Object valueForField(Class<?> t, String name, int seed) {
        if (t == String.class) return name + "-" + seed;
        if (t == Integer.class) return 100 + seed;
        if (t == Long.class) return 1000L + seed;
        if (t == Boolean.class) return seed % 2 == 0;
        if (t == boolean.class) return seed % 2 == 0;
        if (t == UUID.class) return UUID.nameUUIDFromBytes((name + "-" + seed).getBytes());
        if (t == BigDecimal.class) return BigDecimal.valueOf(seed, 1);
        if (t == OffsetDateTime.class) return OffsetDateTime.parse(seed == 1 ? "2024-01-01T00:00:00Z" : "2024-01-02T00:00:00Z");
        if (t == byte[].class) return seed == 1 ? new byte[] {1, 2, 3} : new byte[] {4, 5, 6};
        if (List.class.isAssignableFrom(t)) return seed == 1 ? List.of("a", "b") : List.of("x", "y");
        if (Map.class.isAssignableFrom(t)) return seed == 1 ? Map.of("k", "v") : Map.of("k2", "v2");
        if (Object.class == t) return seed == 1 ? Map.of("obj", 1) : Map.of("obj", 2);
        if (t.isEnum()) {
            Object[] values = t.getEnumConstants();
            return seed == 1 ? values[0] : values[Math.min(1, values.length - 1)];
        }
        try {
            var ctor = t.getDeclaredConstructor();
            ctor.setAccessible(true);
            Object obj = ctor.newInstance();
            for (Field f : instanceFields(t)) {
                f.set(obj, valueForField(f.getType(), f.getName(), seed));
            }
            return obj;
        } catch (ReflectiveOperationException ignored) {
            return seed == 1 ? new LinkedHashMap<>() : new ArrayList<>();
        }
    }
}
