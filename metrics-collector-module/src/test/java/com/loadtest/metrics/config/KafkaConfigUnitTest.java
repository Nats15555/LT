package com.loadtest.metrics.config;

import com.loadtest.metrics.dto.MetricsCollectionEvent;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaConfigUnitTest {

    @Test
    void configBeans_areCreated() {
        KafkaConfig cfg = new KafkaConfig();
        ReflectionTestUtils.setField(cfg, "bootstrapServers", "localhost:9092");
        ReflectionTestUtils.setField(cfg, "groupId", "g1");
        ReflectionTestUtils.setField(cfg, "metricsCollectionTasksTopic", "metrics-collection-tasks");
        ReflectionTestUtils.setField(cfg, "summarizationTasksTopic", "summarization-tasks");

        assertThat(cfg.metricsCollectionTasksTopic().name()).isEqualTo("metrics-collection-tasks");
        assertThat(cfg.summarizationTasksTopic().name()).isEqualTo("summarization-tasks");

        ProducerFactory<String, com.loadtest.metrics.dto.SummarizationTaskEvent> pf = cfg.summarizationProducerFactory();
        assertThat(pf).isNotNull();
        KafkaTemplate<String, com.loadtest.metrics.dto.SummarizationTaskEvent> kt = cfg.summarizationKafkaTemplate();
        assertThat(kt).isNotNull();

        ConsumerFactory<String, MetricsCollectionEvent> cf = cfg.consumerFactory();
        assertThat(cf).isNotNull();
        ConcurrentKafkaListenerContainerFactory<String, MetricsCollectionEvent> lf = cfg.kafkaListenerContainerFactory();
        assertThat(lf).isNotNull();
        assertThat(lf.getContainerProperties().getAckMode())
                .isEqualTo(org.springframework.kafka.listener.ContainerProperties.AckMode.MANUAL);
    }
}

