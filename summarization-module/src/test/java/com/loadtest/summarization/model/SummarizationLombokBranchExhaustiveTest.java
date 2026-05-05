package com.loadtest.summarization.model;

import com.loadtest.summarization.dto.SummarizationTaskEvent;
import com.loadtest.summarization.persistence.SummarizerConfig;
import com.loadtest.summarization.persistence.TaskArtifactsRepository;
import com.loadtest.summarization.persistence.TaskMetricsRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SummarizationLombokBranchExhaustiveTest {

    private static final Class<?>[] TARGET_CLASSES = new Class<?>[] {
            SummarizationTaskEvent.class,
            SummarizerConfig.class,
            TaskArtifactsRepository.ArtifactContent.class,
            TaskMetricsRepository.MetricsRow.class
    };

    @Test
    void equalsHashCode_andBuilders_covered() throws Exception {
        for (Class<?> type : TARGET_CLASSES) {
            assertEqualsBranches(type);
        }
    }

    @Test
    void dataClasses_canEqual_trueAndFalseBranches() throws Exception {
        assertCanEqualBranches(SummarizationTaskEvent.class);
        assertCanEqualBranches(SummarizerConfig.class);
        assertCanEqualBranches(TaskArtifactsRepository.ArtifactContent.class);
        assertCanEqualBranches(TaskMetricsRepository.MetricsRow.class);
    }

    @Test
    void dataClasses_equals_nullFieldBranches() throws Exception {
        assertNullFieldBranches(SummarizationTaskEvent.class);
        assertNullFieldBranches(SummarizerConfig.class);
    }

    @Test
    void dataClasses_equalsSelf_andHashCodeNullBranches() throws Exception {
        assertEqualsSelfAndHashCodeNullBranches(SummarizationTaskEvent.class);
        assertEqualsSelfAndHashCodeNullBranches(SummarizerConfig.class);
    }

    @Test
    void dataClasses_equals_whenBothSidesHaveNullFields() throws Exception {
        assertEqualsWithBothNullFieldValues(SummarizationTaskEvent.class);
        assertEqualsWithBothNullFieldValues(SummarizerConfig.class);
    }

    @Test
    void summarizerConfig_equalsCoversSubclassCanEqual() {
        SummarizerConfig base = SummarizerConfig.builder()
                .id(UUID.randomUUID())
                .name("n")
                .provider("OPENAI")
                .baseUrl("http://x")
                .modelId("m")
                .build();
        SummarizerConfig derived = new SummarizerConfigChild();
        derived.setId(base.getId());
        derived.setName(base.getName());
        derived.setProvider(base.getProvider());
        derived.setBaseUrl(base.getBaseUrl());
        derived.setModelId(base.getModelId());

        assertThat((Boolean) ReflectionTestUtils.invokeMethod(base, "canEqual", derived)).isTrue();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(base, "canEqual", "x")).isFalse();
        assertThat(base).isEqualTo(derived);
        assertThat(derived).isEqualTo(base);
    }

    @Test
    void dataClasses_equals_branchWhenOtherCanEqualReturnsFalse() {
        assertThat(new SummarizationTaskEvent("t", "s"))
                .isNotEqualTo(new SummarizationTaskEventCanEqualFalse());

        UUID id = UUID.randomUUID();
        SummarizerConfig cfg = SummarizerConfig.builder()
                .id(id)
                .name("n")
                .provider("OPENAI")
                .baseUrl("http://x")
                .modelId("m")
                .apiKeyEnvVar("ENV")
                .apiKeyResolved("secret")
                .build();
        SummarizerConfigCanEqualFalse cfgOther = new SummarizerConfigCanEqualFalse();
        cfgOther.setId(cfg.getId());
        cfgOther.setName(cfg.getName());
        cfgOther.setProvider(cfg.getProvider());
        cfgOther.setBaseUrl(cfg.getBaseUrl());
        cfgOther.setModelId(cfg.getModelId());
        cfgOther.setApiKeyEnvVar(cfg.getApiKeyEnvVar());
        cfgOther.setApiKeyResolved(cfg.getApiKeyResolved());
        assertThat(cfg).isNotEqualTo(cfgOther);

        TaskArtifactsRepository.ArtifactContent ac =
                new TaskArtifactsRepository.ArtifactContent("f.log", "text");
        assertThat(ac).isNotEqualTo(new ArtifactContentCanEqualFalse("f.log", "text"));

        Instant collected = Instant.parse("2024-06-01T12:00:00Z");
        TaskMetricsRepository.MetricsRow mr =
                new TaskMetricsRepository.MetricsRow("prom", "http://u", "{}", collected);
        assertThat(mr).isNotEqualTo(new MetricsRowCanEqualFalse("prom", "http://u", "{}", collected));
    }

    @Test
    void artifactContent_equals_hashCode_andCanEqualBranches() {
        TaskArtifactsRepository.ArtifactContent base = new TaskArtifactsRepository.ArtifactContent("f.log", "body");
        assertThat(base).isEqualTo(base);
        assertThat(base).isNotEqualTo(null);
        assertThat(base).isNotEqualTo("x");
        assertThat(base).isNotEqualTo(new TaskArtifactsRepository.ArtifactContent("f.log", "other"));
        assertThat(new TaskArtifactsRepository.ArtifactContent("f.log", null)).isNotEqualTo(base);
        assertThat(new TaskArtifactsRepository.ArtifactContent(null, "body")).isNotEqualTo(base);

        TaskArtifactsRepository.ArtifactContent na = new TaskArtifactsRepository.ArtifactContent(null, null);
        TaskArtifactsRepository.ArtifactContent nb = new TaskArtifactsRepository.ArtifactContent(null, null);
        assertThat(na).isEqualTo(nb);
        assertThat(na.hashCode()).isEqualTo(nb.hashCode());
        na.hashCode();

        ArtifactContentChild child = new ArtifactContentChild("f.log", "body");
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(base, "canEqual", child)).isTrue();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(base, "canEqual", "x")).isFalse();
        assertThat(base).isEqualTo(child);
        assertThat(child).isEqualTo(base);
    }

    @Test
    void metricsRow_equals_hashCode_andCanEqualBranches() {
        Instant i1 = Instant.parse("2024-06-01T12:00:00Z");
        Instant i2 = Instant.parse("2024-06-02T12:00:00Z");
        TaskMetricsRepository.MetricsRow base = new TaskMetricsRepository.MetricsRow("prom", "http://u", "{}", i1);

        assertThat(base).isEqualTo(base);
        assertThat(base).isNotEqualTo(null);
        assertThat(base).isNotEqualTo("x");
        assertThat(base).isNotEqualTo(new TaskMetricsRepository.MetricsRow("other", "http://u", "{}", i1));
        assertThat(base).isNotEqualTo(new TaskMetricsRepository.MetricsRow("prom", "http://other", "{}", i1));
        assertThat(base).isNotEqualTo(new TaskMetricsRepository.MetricsRow("prom", "http://u", "{\"a\":1}", i1));
        assertThat(base).isNotEqualTo(new TaskMetricsRepository.MetricsRow("prom", "http://u", "{}", i2));

        assertThat(new TaskMetricsRepository.MetricsRow(null, "http://u", "{}", i1)).isNotEqualTo(base);
        assertThat(new TaskMetricsRepository.MetricsRow("prom", null, "{}", i1)).isNotEqualTo(base);
        assertThat(new TaskMetricsRepository.MetricsRow("prom", "http://u", null, i1)).isNotEqualTo(base);
        assertThat(new TaskMetricsRepository.MetricsRow("prom", "http://u", "{}", null)).isNotEqualTo(base);

        TaskMetricsRepository.MetricsRow na = new TaskMetricsRepository.MetricsRow(null, null, null, null);
        TaskMetricsRepository.MetricsRow nb = new TaskMetricsRepository.MetricsRow(null, null, null, null);
        assertThat(na).isEqualTo(nb);
        assertThat(na.hashCode()).isEqualTo(nb.hashCode());
        na.hashCode();

        MetricsRowChild child = new MetricsRowChild("prom", "http://u", "{}", i1);
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(base, "canEqual", child)).isTrue();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(base, "canEqual", "x")).isFalse();
        assertThat(base).isEqualTo(child);
        assertThat(child).isEqualTo(base);
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

    private static Object valueForField(Class<?> t, String name, int seed) {
        if (t == String.class) return name + "-" + seed;
        if (t == Integer.class || t == int.class) return 10 + seed;
        if (t == Long.class || t == long.class) return 100L + seed;
        if (t == Boolean.class || t == boolean.class) return seed % 2 == 0;
        if (t == UUID.class) return UUID.nameUUIDFromBytes((name + "-" + seed).getBytes());
        if (t == Instant.class) return Instant.ofEpochMilli(1_000L + seed);
        if (List.class.isAssignableFrom(t)) return seed == 1 ? List.of("a") : List.of("b");
        if (Map.class.isAssignableFrom(t)) return seed == 1 ? Map.of("k", "v") : Map.of("k2", "v2");
        try {
            var ctor = t.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (Exception ignored) {
            return seed == 1 ? new LinkedHashMap<>() : new ArrayList<>();
        }
    }

    private static final class SummarizerConfigChild extends SummarizerConfig { }

    private static final class SummarizerConfigCanEqualFalse extends SummarizerConfig {
        @Override
        protected boolean canEqual(Object other) {
            return false;
        }
    }

    private static final class SummarizationTaskEventCanEqualFalse extends SummarizationTaskEvent {
        @Override
        protected boolean canEqual(Object other) {
            return false;
        }
    }

    private static final class ArtifactContentChild extends TaskArtifactsRepository.ArtifactContent {
        ArtifactContentChild(String fileName, String textContent) {
            super(fileName, textContent);
        }
    }

    private static final class ArtifactContentCanEqualFalse extends TaskArtifactsRepository.ArtifactContent {
        ArtifactContentCanEqualFalse(String fileName, String textContent) {
            super(fileName, textContent);
        }

        @Override
        protected boolean canEqual(Object other) {
            return false;
        }
    }

    private static final class MetricsRowChild extends TaskMetricsRepository.MetricsRow {
        MetricsRowChild(String sourceType, String endpointUrl, String metricsDataJson, Instant collectedAt) {
            super(sourceType, endpointUrl, metricsDataJson, collectedAt);
        }
    }

    private static final class MetricsRowCanEqualFalse extends TaskMetricsRepository.MetricsRow {
        MetricsRowCanEqualFalse(String sourceType, String endpointUrl, String metricsDataJson, Instant collectedAt) {
            super(sourceType, endpointUrl, metricsDataJson, collectedAt);
        }

        @Override
        protected boolean canEqual(Object other) {
            return false;
        }
    }
}

