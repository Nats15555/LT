package com.loadtest.metrics.model;

import com.loadtest.metrics.config.MetricsCollectorProperties;
import com.loadtest.metrics.dto.MetricsCollectionEvent;
import com.loadtest.metrics.dto.MetricsCollectionRequest;
import com.loadtest.metrics.dto.MetricsCollectionResponse;
import com.loadtest.metrics.dto.SummarizationTaskEvent;
import com.loadtest.metrics.persistence.TaskMetricsConfigRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LombokEqualsBranchExhaustiveTest {

    private static final Class<?>[] LOMBOK_TARGETS = new Class<?>[] {
            MetricsCollectorProperties.class
    };

    @Test
    void recordDto_equalsBranches() {
        SummarizationTaskEvent sumA = new SummarizationTaskEvent("t", "s");
        assertThat(sumA).isEqualTo(new SummarizationTaskEvent("t", "s"));
        assertThat(sumA).isNotEqualTo(new SummarizationTaskEvent("x", "s"));

        MetricsCollectionEvent eventA = new MetricsCollectionEvent("t", 1L, 2L);
        assertThat(eventA).isEqualTo(new MetricsCollectionEvent("t", 1L, 2L));
        assertThat(eventA).isNotEqualTo(new MetricsCollectionEvent("x", 1L, 2L));

        MetricsCollectionRequest.MetricsRequestItem item =
                new MetricsCollectionRequest.MetricsRequestItem("n", "GET", "http://u", Map.of("h", "v"), null, null);
        MetricsCollectionRequest reqA = new MetricsCollectionRequest("t", List.of(item), 0, 1L, 2L);
        assertThat(reqA).isEqualTo(new MetricsCollectionRequest("t", List.of(item), 0, 1L, 2L));
        assertThat(reqA).isNotEqualTo(new MetricsCollectionRequest("x", List.of(item), 0, 1L, 2L));

        MetricsCollectionResponse.SummaryResult summary =
                new MetricsCollectionResponse.SummaryResult("ok", "text", Map.of("d", 1));
        MetricsCollectionResponse respA =
                new MetricsCollectionResponse("t", "ok", "m", Map.of("k", 1), summary, 1L, 2L);
        assertThat(respA).isEqualTo(
                new MetricsCollectionResponse("t", "ok", "m", Map.of("k", 1), summary, 1L, 2L));
        assertThat(respA).isNotEqualTo(
                new MetricsCollectionResponse("t2", "ok", "m", Map.of("k", 1), summary, 1L, 2L));
    }

    @Test
    void lombokGenerated_branches() throws Exception {
        for (Class<?> t : LOMBOK_TARGETS) {
            Object a = newInstance(t);
            setFields(a);
            Object b = cloneObj(t, a);
            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
            assertThat(a).isNotEqualTo(null);
            assertThat(a).isNotEqualTo("x");
            assertThat(a.toString()).isNotBlank();
            for (Field f : fields(t)) {
                Object d = cloneObj(t, a);
                f.set(d, value(f.getType(), f.getName(), 2));
                assertThat(a).isNotEqualTo(d);
                if (!f.getType().isPrimitive()) {
                    Object ln = cloneObj(t, a);
                    Object rn = cloneObj(t, a);
                    f.set(ln, null);
                    f.set(rn, null);
                    assertThat(ln).isEqualTo(rn);
                }
            }
            instantiateBuilderIfPresent(t);
        }
        TaskMetricsConfigRepository.TaskMetricsConfig cfg =
                new TaskMetricsConfigRepository.TaskMetricsConfig("{\"a\":1}");
        assertThat(cfg.metricsConfigJson()).isEqualTo("{\"a\":1}");
        assertThat(cfg).isEqualTo(new TaskMetricsConfigRepository.TaskMetricsConfig("{\"a\":1}"));
    }

    private static void instantiateBuilderIfPresent(Class<?> t) {
        try {
            Object b = t.getMethod("builder").invoke(null);
            for (Field f : fields(t)) {
                try {
                    b.getClass().getMethod(f.getName(), f.getType()).invoke(b, value(f.getType(), f.getName(), 1));
                } catch (NoSuchMethodException ignored) {
                }
            }
            assertThat(b.getClass().getMethod("build").invoke(b)).isNotNull();
        } catch (ReflectiveOperationException ignored) {
        }
    }

    private static Object newInstance(Class<?> t) throws Exception {
        var c = t.getDeclaredConstructor();
        c.setAccessible(true);
        return c.newInstance();
    }

    private static List<Field> fields(Class<?> t) {
        List<Field> out = new ArrayList<>();
        for (Field f : t.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) {
                f.setAccessible(true);
                out.add(f);
            }
        }
        return out;
    }

    private static void setFields(Object o) throws Exception {
        for (Field f : fields(o.getClass())) {
            f.set(o, value(f.getType(), f.getName(), 1));
        }
    }

    private static Object cloneObj(Class<?> t, Object src) throws Exception {
        Object copy = newInstance(t);
        for (Field f : fields(t)) {
            f.set(copy, f.get(src));
        }
        return copy;
    }

    private static Object value(Class<?> t, String n, int s) {
        if (t == String.class) {
            return n + s;
        }
        if (t == Integer.class) {
            return s;
        }
        if (t == Long.class) {
            return (long) s;
        }
        if (t == Boolean.class || t == boolean.class) {
            return s % 2 == 0;
        }
        if (List.class.isAssignableFrom(t)) {
            return s == 1 ? List.of("a") : List.of("b");
        }
        if (Map.class.isAssignableFrom(t)) {
            return s == 1 ? Map.of("k", "v") : Map.of("k2", "v2");
        }
        if (t == Object.class) {
            return s == 1 ? Map.of("o", 1) : "x";
        }
        try {
            Object o = t.getDeclaredConstructor().newInstance();
            for (Field f : fields(t)) {
                f.set(o, value(f.getType(), f.getName(), s));
            }
            return o;
        } catch (ReflectiveOperationException ignored) {
            return s == 1 ? new LinkedHashMap<>() : new ArrayList<>();
        }
    }
}
