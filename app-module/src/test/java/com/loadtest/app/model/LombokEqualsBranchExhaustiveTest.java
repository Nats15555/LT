package com.loadtest.app.model;

import com.loadtest.app.dto.ArtifactInfoDto;
import com.loadtest.app.dto.CreateDockerProfileRequest;
import com.loadtest.app.dto.CreateLoadTestToolRequest;
import com.loadtest.app.dto.CreateSummarizerRequest;
import com.loadtest.app.dto.DockerProfileDto;
import com.loadtest.app.dto.LoadTestToolDto;
import com.loadtest.app.dto.MetricsItemDto;
import com.loadtest.app.dto.SummarizationTaskEvent;
import com.loadtest.app.dto.SummarizerModelDto;
import com.loadtest.app.dto.SummaryItemDto;
import com.loadtest.app.dto.TaskHistoryItemDto;
import com.loadtest.app.dto.TaskQueueItemDto;
import com.loadtest.app.dto.TestTaskEvent;
import com.loadtest.app.dto.TestTaskMessage;
import com.loadtest.app.dto.UpdateDockerProfileRequest;
import com.loadtest.app.dto.UpdateLoadTestToolRequest;
import com.loadtest.app.dto.UpdateSummarizerRequest;
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

import static org.assertj.core.api.Assertions.assertThat;

class LombokEqualsBranchExhaustiveTest {

    private static final Class<?>[] TARGET_CLASSES = new Class<?>[] {
            ArtifactInfoDto.class,
            CreateDockerProfileRequest.class,
            CreateLoadTestToolRequest.class,
            CreateSummarizerRequest.class,
            DockerProfileDto.class,
            LoadTestToolDto.class,
            MetricsItemDto.class,
            SummarizationTaskEvent.class,
            SummarizerModelDto.class,
            SummaryItemDto.class,
            TaskHistoryItemDto.class,
            TaskQueueItemDto.class,
            TestTaskEvent.class,
            TestTaskMessage.class,
            TestTaskMessage.MetricsConfig.class,
            TestTaskMessage.MetricsConfig.MetricsRequest.class,
            UpdateDockerProfileRequest.class,
            UpdateLoadTestToolRequest.class,
            UpdateSummarizerRequest.class,
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
    void equalsHashCode_branchesCoveredForAllFields() throws Exception {
        for (Class<?> type : TARGET_CLASSES) {
            assertEqualsBranchesPerField(type);
        }
        assertThat(TestTaskStatus.values()).contains(TestTaskStatus.PENDING, TestTaskStatus.PROCESSING);
        assertThat(TestTaskStatus.valueOf("PENDING")).isEqualTo(TestTaskStatus.PENDING);
    }

    private static void assertEqualsBranchesPerField(Class<?> type) throws Exception {
        instantiateViaBuilderAndAllArgs(type);

        Object base = newInstance(type);
        setAllFields(base, 1);
        Object same = cloneWithAllFields(type, base);

        assertThat(base).isEqualTo(same);
        assertThat(base.hashCode()).isEqualTo(same.hashCode());
        assertThat(base).isNotEqualTo(null);
        assertThat(base).isNotEqualTo("other");
        assertThat(base.toString()).isNotBlank();

        List<Field> fields = instanceFields(type);
        for (Field f : fields) {
            Object diff = cloneWithAllFields(type, base);
            f.set(diff, valueForField(f.getType(), f.getName(), 2));
            assertThat(base).as(type.getSimpleName() + "." + f.getName()).isNotEqualTo(diff);

            if (!f.getType().isPrimitive()) {
                Object leftNull = cloneWithAllFields(type, base);
                Object rightNull = cloneWithAllFields(type, base);
                f.set(leftNull, null);
                f.set(rightNull, null);
                assertThat(leftNull).as(type.getSimpleName() + "." + f.getName() + " both null").isEqualTo(rightNull);

                Object rightNonNull = cloneWithAllFields(type, base);
                f.set(rightNonNull, valueForField(f.getType(), f.getName(), 3));
                assertThat(leftNull).as(type.getSimpleName() + "." + f.getName() + " null vs non-null").isNotEqualTo(rightNonNull);

                Object leftNonNull = cloneWithAllFields(type, base);
                Object rightNullFromNonNull = cloneWithAllFields(type, base);
                f.set(rightNullFromNonNull, null);
                assertThat(leftNonNull)
                        .as(type.getSimpleName() + "." + f.getName() + " non-null vs null")
                        .isNotEqualTo(rightNullFromNonNull);
            }
        }
    }

    private static void instantiateViaBuilderAndAllArgs(Class<?> type) {
        try {
            Object b = type.getMethod("builder").invoke(null);
            for (Field f : instanceFields(type)) {
                try {
                    var m = b.getClass().getMethod(f.getName(), f.getType());
                    m.invoke(b, valueForField(f.getType(), f.getName(), 1));
                } catch (NoSuchMethodException ignored) {
                }
            }
            Object built = b.getClass().getMethod("build").invoke(b);
            assertThat(built).isNotNull();
        } catch (Exception ignored) {
        }

        try {
            List<Field> fs = instanceFields(type);
            Class<?>[] sig = fs.stream().map(Field::getType).toArray(Class[]::new);
            Object[] args = fs.stream().map(f -> valueForField(f.getType(), f.getName(), 1)).toArray();
            var ctor = type.getDeclaredConstructor(sig);
            ctor.setAccessible(true);
            Object x = ctor.newInstance(args);
            assertThat(x).isNotNull();
        } catch (Exception ignored) {
        }
    }

    private static Object newInstance(Class<?> type) throws Exception {
        var ctor = type.getDeclaredConstructor();
        ctor.setAccessible(true);
        return ctor.newInstance();
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

    @SuppressWarnings({"rawtypes", "unchecked"})
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
        } catch (Exception ignored) {
            return seed == 1 ? new LinkedHashMap<>() : new ArrayList<>();
        }
    }
}
