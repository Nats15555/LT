package com.loadtest.metrics;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

class MetricsCollectorApplicationTest {

    @Test
    void main_invokesSpringApplicationRun() {
        try (MockedStatic<SpringApplication> boot = Mockito.mockStatic(SpringApplication.class)) {
            ConfigurableApplicationContext ctx = Mockito.mock(ConfigurableApplicationContext.class);
            boot.when(() -> SpringApplication.run(eq(MetricsCollectorApplication.class), any(String[].class)))
                    .thenReturn(ctx);
            MetricsCollectorApplication.main(new String[] {"--x=y"});
            boot.verify(() -> SpringApplication.run(eq(MetricsCollectorApplication.class), any(String[].class)));
        }
    }

    @Test
    void class_hasExpectedAnnotations_andConstructor() {
        assertThat(new MetricsCollectorApplication()).isNotNull();
        assertThat(AnnotationUtils.findAnnotation(MetricsCollectorApplication.class, SpringBootApplication.class)).isNotNull();
        assertThat(AnnotationUtils.findAnnotation(MetricsCollectorApplication.class, EnableKafka.class)).isNotNull();
        assertThat(AnnotationUtils.findAnnotation(MetricsCollectorApplication.class, EnableScheduling.class)).isNotNull();
    }
}

