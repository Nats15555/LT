package com.loadtest.metrics.config;

import com.loadtest.metrics.dto.MetricsCollectionEvent;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaConfigContextTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(KafkaConfig.class)
            .withPropertyValues(
                    "spring.kafka.bootstrap-servers=localhost:9092",
                    "spring.kafka.consumer.group-id=g1",
                    "kafka.topic.metrics-collection-tasks=metrics-collection-tasks",
                    "kafka.topic.summarization-tasks=summarization-tasks");

    @Test
    void config_registersAllKafkaBeans() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(ProducerFactory.class);
            assertThat(ctx).hasSingleBean(ConsumerFactory.class);
            assertThat(ctx).hasSingleBean(KafkaTemplate.class);
            assertThat(ctx).hasSingleBean(ConcurrentKafkaListenerContainerFactory.class);

            ConcurrentKafkaListenerContainerFactory<String, MetricsCollectionEvent> f =
                    ctx.getBean("kafkaListenerContainerFactory", ConcurrentKafkaListenerContainerFactory.class);
            assertThat(f.getContainerProperties().getAckMode()).isEqualTo(ContainerProperties.AckMode.MANUAL);
            assertThat(ctx.getBean("summarizationKafkaTemplate", KafkaTemplate.class)).isNotNull();
        });
    }

    @Test
    void topics_areCreatedFromProperties() {
        runner.run(ctx -> {
            org.apache.kafka.clients.admin.NewTopic m = ctx.getBean("metricsCollectionTasksTopic", org.apache.kafka.clients.admin.NewTopic.class);
            org.apache.kafka.clients.admin.NewTopic s = ctx.getBean("summarizationTasksTopic", org.apache.kafka.clients.admin.NewTopic.class);
            assertThat(m.name()).isEqualTo("metrics-collection-tasks");
            assertThat(s.name()).isEqualTo("summarization-tasks");
        });
    }
}

