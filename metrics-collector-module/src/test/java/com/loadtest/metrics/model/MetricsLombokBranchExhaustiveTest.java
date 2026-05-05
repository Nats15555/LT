package com.loadtest.metrics.model;

import com.loadtest.metrics.dto.MetricsCollectionEvent;
import com.loadtest.metrics.dto.MetricsCollectionRequest;
import com.loadtest.metrics.dto.MetricsCollectionResponse;
import com.loadtest.metrics.dto.SummarizationTaskEvent;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MetricsLombokBranchExhaustiveTest {

    private static final Class<?>[] TARGET_CLASSES = new Class<?>[] {
            MetricsCollectionEvent.class,
            MetricsCollectionRequest.class,
            MetricsCollectionRequest.MetricsRequestItem.class,
            MetricsCollectionResponse.class,
            MetricsCollectionResponse.SummaryResult.class,
            SummarizationTaskEvent.class
    };

    @Test
    void equalsHashCode_andBuilders_covered() throws Exception {
        for (Class<?> type : TARGET_CLASSES) {
            assertEqualsBranches(type);
        }
    }

    @Test
    void metricsCollectionResponse_equalsCoversCanEqualBranch() {
        MetricsCollectionResponse base = MetricsCollectionResponse.builder()
                .taskId("t")
                .status("SUCCESS")
                .message("m")
                .metrics(Map.of("k", "v"))
                .summary(MetricsCollectionResponse.SummaryResult.builder().status("SUCCESS").summary("s").build())
                .collectionStartTime(1L)
                .collectionEndTime(2L)
                .build();

        MetricsCollectionResponse derived = new MetricsCollectionResponseChild();
        derived.setTaskId("t");
        derived.setStatus("SUCCESS");
        derived.setMessage("m");
        derived.setMetrics(Map.of("k", "v"));
        derived.setSummary(MetricsCollectionResponse.SummaryResult.builder().status("SUCCESS").summary("s").build());
        derived.setCollectionStartTime(1L);
        derived.setCollectionEndTime(2L);

        assertThat((Boolean) ReflectionTestUtils.invokeMethod(base, "canEqual", derived)).isTrue();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(base, "canEqual", "x")).isFalse();
        assertThat(base).isEqualTo(derived);
        assertThat(derived).isEqualTo(base);
    }

    @Test
    void summaryResult_equalsCoversCanEqualBranch() {
        MetricsCollectionResponse.SummaryResult base = MetricsCollectionResponse.SummaryResult.builder()
                .status("SUCCESS")
                .summary("s")
                .details(Map.of("k", "v"))
                .build();

        MetricsCollectionResponse.SummaryResult derived = new SummaryResultChild();
        derived.setStatus("SUCCESS");
        derived.setSummary("s");
        derived.setDetails(Map.of("k", "v"));

        assertThat((Boolean) ReflectionTestUtils.invokeMethod(base, "canEqual", derived)).isTrue();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(base, "canEqual", "x")).isFalse();
        assertThat(base).isEqualTo(derived);
        assertThat(derived).isEqualTo(base);
    }

    @Test
    void dataClasses_canEqual_trueAndFalseBranches() throws Exception {
        assertCanEqualBranches(MetricsCollectionEvent.class);
        assertCanEqualBranches(MetricsCollectionRequest.class);
        assertCanEqualBranches(MetricsCollectionRequest.MetricsRequestItem.class);
        assertCanEqualBranches(MetricsCollectionResponse.class);
        assertCanEqualBranches(MetricsCollectionResponse.SummaryResult.class);
        assertCanEqualBranches(SummarizationTaskEvent.class);
    }

    @Test
    void dataClasses_equals_nullFieldBranches() throws Exception {
        assertNullFieldBranches(MetricsCollectionEvent.class);
        assertNullFieldBranches(MetricsCollectionRequest.class);
        assertNullFieldBranches(MetricsCollectionRequest.MetricsRequestItem.class);
        assertNullFieldBranches(MetricsCollectionResponse.class);
        assertNullFieldBranches(MetricsCollectionResponse.SummaryResult.class);
        assertNullFieldBranches(SummarizationTaskEvent.class);
    }

    @Test
    void dataClasses_equalsSelf_andHashCodeNullBranches() throws Exception {
        assertEqualsSelfAndHashCodeNullBranches(MetricsCollectionEvent.class);
        assertEqualsSelfAndHashCodeNullBranches(MetricsCollectionRequest.class);
        assertEqualsSelfAndHashCodeNullBranches(MetricsCollectionRequest.MetricsRequestItem.class);
        assertEqualsSelfAndHashCodeNullBranches(MetricsCollectionResponse.class);
        assertEqualsSelfAndHashCodeNullBranches(MetricsCollectionResponse.SummaryResult.class);
        assertEqualsSelfAndHashCodeNullBranches(SummarizationTaskEvent.class);
    }

    @Test
    void dataClasses_equals_whenBothSidesHaveNullFields() throws Exception {
        assertEqualsWithBothNullFieldValues(MetricsCollectionEvent.class);
        assertEqualsWithBothNullFieldValues(MetricsCollectionRequest.class);
        assertEqualsWithBothNullFieldValues(MetricsCollectionRequest.MetricsRequestItem.class);
        assertEqualsWithBothNullFieldValues(MetricsCollectionResponse.class);
        assertEqualsWithBothNullFieldValues(MetricsCollectionResponse.SummaryResult.class);
        assertEqualsWithBothNullFieldValues(SummarizationTaskEvent.class);
    }

    @Test
    void dataClasses_equals_branchWhenOtherCanEqualReturnsFalse() {
        assertThat(new MetricsCollectionEvent("t", 1L, 2L))
                .isNotEqualTo(new MetricsCollectionEventCanEqualFalse());
        assertThat(MetricsCollectionRequest.builder().taskId("t").build())
                .isNotEqualTo(new MetricsCollectionRequestCanEqualFalse());
        assertThat(MetricsCollectionResponse.builder().taskId("t").build())
                .isNotEqualTo(new MetricsCollectionResponseCanEqualFalse());
        assertThat(MetricsCollectionResponse.SummaryResult.builder().status("SUCCESS").summary("s").build())
                .isNotEqualTo(new SummaryResultCanEqualFalse());
        assertThat(new SummarizationTaskEvent("t", "s"))
                .isNotEqualTo(new SummarizationTaskEventCanEqualFalse());
        assertThat(new MetricsCollectionRequest.MetricsRequestItem("n", "GET", "u", null, null, null))
                .isNotEqualTo(new MetricsRequestItemCanEqualFalse());
    }

    private static void assertEqualsBranches(Class<?> type) throws Exception {
        instantiateViaBuilderAndAllArgs(type);
        Object base = newInstance(type);
        setAllFields(base, 1);
        Object same = cloneWithAllFields(type, base);
        assertThat(base).isEqualTo(same);
        assertThat(base.hashCode()).isEqualTo(same.hashCode());
        assertThat(base).isNotEqualTo(null);
        assertThat(base).isNotEqualTo("x");
        for (Field f : instanceFields(type)) {
            Object diff = cloneWithAllFields(type, base);
            f.set(diff, valueForField(f.getType(), f.getName(), 2));
            assertThat(base).isNotEqualTo(diff);
        }
    }

    private static void instantiateViaBuilderAndAllArgs(Class<?> type) {
        try {
            Object b = type.getMethod("builder").invoke(null);
            for (Field f : instanceFields(type)) {
                try {
                    b.getClass().getMethod(f.getName(), f.getType()).invoke(b, valueForField(f.getType(), f.getName(), 1));
                } catch (NoSuchMethodException ignored) {
                }
            }
            assertThat(b.getClass().getMethod("build").invoke(b)).isNotNull();
        } catch (Exception ignored) {
        }
        try {
            List<Field> fs = instanceFields(type);
            Class<?>[] sig = fs.stream().map(Field::getType).toArray(Class[]::new);
            Object[] args = fs.stream().map(f -> valueForField(f.getType(), f.getName(), 1)).toArray();
            var ctor = type.getDeclaredConstructor(sig);
            ctor.setAccessible(true);
            assertThat(ctor.newInstance(args)).isNotNull();
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
            if (!Modifier.isStatic(f.getModifiers())) {
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

    private static Object valueForField(Class<?> t, String name, int seed) {
        if (t == String.class) return name + "-" + seed;
        if (t == Integer.class || t == int.class) return 10 + seed;
        if (t == Long.class || t == long.class) return 100L + seed;
        if (t == Boolean.class || t == boolean.class) return seed % 2 == 0;
        if (t == UUID.class) return UUID.nameUUIDFromBytes((name + "-" + seed).getBytes());
        if (List.class.isAssignableFrom(t)) return seed == 1 ? List.of("a") : List.of("b");
        if (Map.class.isAssignableFrom(t)) return seed == 1 ? Map.of("k", "v") : Map.of("k2", "v2");
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

    private static final class MetricsCollectionResponseChild extends MetricsCollectionResponse { }

    private static final class SummaryResultChild extends MetricsCollectionResponse.SummaryResult { }

    private static final class MetricsCollectionEventCanEqualFalse extends MetricsCollectionEvent {
        @Override
        protected boolean canEqual(Object other) { return false; }
    }

    private static final class MetricsCollectionRequestCanEqualFalse extends MetricsCollectionRequest {
        @Override
        protected boolean canEqual(Object other) { return false; }
    }

    private static final class MetricsCollectionResponseCanEqualFalse extends MetricsCollectionResponse {
        @Override
        protected boolean canEqual(Object other) { return false; }
    }

    private static final class SummarizationTaskEventCanEqualFalse extends SummarizationTaskEvent {
        @Override
        protected boolean canEqual(Object other) { return false; }
    }

    private static final class MetricsRequestItemCanEqualFalse extends MetricsCollectionRequest.MetricsRequestItem {
        @Override
        protected boolean canEqual(Object other) { return false; }
    }

    private static final class SummaryResultCanEqualFalse extends MetricsCollectionResponse.SummaryResult {
        @Override
        protected boolean canEqual(Object other) { return false; }
    }
}

