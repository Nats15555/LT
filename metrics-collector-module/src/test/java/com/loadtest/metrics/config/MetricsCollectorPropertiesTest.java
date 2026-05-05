package com.loadtest.metrics.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MetricsCollectorPropertiesTest {

    @Test
    void dataBranches_equalsHashCodeAndCanEqual() {
        MetricsCollectorProperties a = new MetricsCollectorProperties();
        a.setHostOverrides(Map.of("prometheus", "localhost"));

        MetricsCollectorProperties b = new MetricsCollectorProperties();
        b.setHostOverrides(Map.of("prometheus", "localhost"));

        assertThat(a).isEqualTo(a);
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(a).isNotEqualTo(null);
        assertThat(a).isNotEqualTo("x");

        MetricsCollectorProperties withNull = new MetricsCollectorProperties();
        withNull.setHostOverrides(null);
        MetricsCollectorProperties withNull2 = new MetricsCollectorProperties();
        withNull2.setHostOverrides(null);
        assertThat(withNull).isEqualTo(withNull2);
        assertThat(withNull.hashCode()).isEqualTo(withNull2.hashCode());
        assertThat(withNull).isNotEqualTo(a);
        assertThat(a).isNotEqualTo(withNull);

        assertThat((Boolean) ReflectionTestUtils.invokeMethod(a, "canEqual", b)).isTrue();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(a, "canEqual", "x")).isFalse();
    }

    @Test
    void equals_whenOtherCanEqualReturnsFalse_branch() {
        MetricsCollectorProperties base = new MetricsCollectorProperties();
        base.setHostOverrides(Map.of("prometheus", "localhost"));
        assertThat(base).isNotEqualTo(new MetricsCollectorPropertiesCanEqualFalse());
    }

    private static final class MetricsCollectorPropertiesCanEqualFalse extends MetricsCollectorProperties {
        @Override
        protected boolean canEqual(Object other) { return false; }
    }
}
