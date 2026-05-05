package com.loadtest.execution;

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

class ExecutionApplicationTest {

    @Test
    void main_startsSpring() {
        try (MockedStatic<SpringApplication> boot = Mockito.mockStatic(SpringApplication.class)) {
            ConfigurableApplicationContext ctx = Mockito.mock(ConfigurableApplicationContext.class);
            boot.when(() -> SpringApplication.run(eq(ExecutionApplication.class), any(String[].class))).thenReturn(ctx);
            ExecutionApplication.main(new String[] {"--spring.main.banner-mode=off"});
            boot.verify(() -> SpringApplication.run(eq(ExecutionApplication.class), any(String[].class)));
        }
    }

    @Test
    void hasExpectedAnnotations_andConstructor() {
        assertThat(new ExecutionApplication()).isNotNull();
        assertThat(AnnotationUtils.findAnnotation(ExecutionApplication.class, SpringBootApplication.class)).isNotNull();
        assertThat(AnnotationUtils.findAnnotation(ExecutionApplication.class, EnableKafka.class)).isNotNull();
        assertThat(AnnotationUtils.findAnnotation(ExecutionApplication.class, EnableScheduling.class)).isNotNull();
    }
}

