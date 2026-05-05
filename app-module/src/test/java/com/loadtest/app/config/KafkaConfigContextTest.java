package com.loadtest.app.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.KafkaTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaConfigContextTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(KafkaConfig.class)
            .withPropertyValues("spring.kafka.bootstrap-servers=127.0.0.1:9092");

    @Test
    void registersBothKafkaTemplates() {
        runner.run(ctx -> {
            assertThat(ctx.getBean("kafkaTemplate", KafkaTemplate.class)).isNotNull();
            assertThat(ctx.getBean("summarizationKafkaTemplate", KafkaTemplate.class)).isNotNull();
            assertThat(ctx.getBean("producerFactory")).isNotNull();
            assertThat(ctx.getBean("summarizationProducerFactory")).isNotNull();
        });
    }
}
