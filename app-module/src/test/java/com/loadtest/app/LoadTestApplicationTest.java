package com.loadtest.app;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.boot.SpringApplication;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

class LoadTestApplicationTest {

    @Test
    void mainStartsSpringBoot() {
        try (MockedStatic<SpringApplication> boot = Mockito.mockStatic(SpringApplication.class)) {
            ConfigurableApplicationContext ctx = Mockito.mock(ConfigurableApplicationContext.class);
            String[] args = new String[]{"--spring.main.banner-mode=off"};
            boot.when(() -> SpringApplication.run(eq(LoadTestApplication.class), any(String[].class)))
                    .thenReturn(ctx);
            LoadTestApplication.main(args);
            boot.verify(() -> SpringApplication.run(LoadTestApplication.class, args));
        }
    }

    @Test
    void classHasExpectedAnnotations_andConstructorCovered() {
        LoadTestApplication app = new LoadTestApplication();
        org.assertj.core.api.Assertions.assertThat(app).isNotNull();
        org.assertj.core.api.Assertions.assertThat(AnnotationUtils.findAnnotation(LoadTestApplication.class, SpringBootApplication.class)).isNotNull();
        org.assertj.core.api.Assertions.assertThat(AnnotationUtils.findAnnotation(LoadTestApplication.class, EnableScheduling.class)).isNotNull();
    }
}
