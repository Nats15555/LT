package com.loadtest.execution.config;

import com.loadtest.execution.dto.MetricsCollectionEvent;
import com.loadtest.execution.dto.TestTaskEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaConfigTest {

    private static KafkaConfig newKafkaConfigWithAllConsumerFields() {
        KafkaConfig cfg = new KafkaConfig();
        ReflectionTestUtils.setField(cfg, "testTasksTopicName", "test-tasks");
        ReflectionTestUtils.setField(cfg, "metricsCollectionTasksTopicName", "metrics-collection-tasks");
        ReflectionTestUtils.setField(cfg, "bootstrapServers", "localhost:9092");
        ReflectionTestUtils.setField(cfg, "groupId", "execution-test-group");
        ReflectionTestUtils.setField(cfg, "autoOffsetReset", "earliest");
        ReflectionTestUtils.setField(cfg, "maxPollIntervalMs", 1_800_000);
        ReflectionTestUtils.setField(cfg, "maxPollRecords", 1);
        ReflectionTestUtils.setField(cfg, "consumerConcurrency", 3);
        return cfg;
    }

    @Test
    void beans_areCreated_andFactoriesHaveExpectedBootstrap() {
        KafkaConfig cfg = newKafkaConfigWithAllConsumerFields();

        assertThat(cfg.testTasksTopic().name()).isEqualTo("test-tasks");
        assertThat(cfg.metricsCollectionTasksTopic().name()).isEqualTo("metrics-collection-tasks");

        ProducerFactory<String, MetricsCollectionEvent> pf = cfg.metricsCollectionProducerFactory();
        assertThat(pf).isInstanceOf(DefaultKafkaProducerFactory.class);
        assertThat(((DefaultKafkaProducerFactory<String, MetricsCollectionEvent>) pf).getConfigurationProperties())
                .containsEntry("bootstrap.servers", "localhost:9092");

        KafkaTemplate<String, MetricsCollectionEvent> tpl = cfg.metricsCollectionKafkaTemplate();
        assertThat(tpl).isNotNull();

        ConsumerFactory<String, TestTaskEvent> cf = cfg.consumerFactory();
        assertThat(cf).isInstanceOf(DefaultKafkaConsumerFactory.class);
        assertThat(((DefaultKafkaConsumerFactory<String, TestTaskEvent>) cf).getConfigurationProperties())
                .containsEntry(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092")
                .containsEntry(ConsumerConfig.GROUP_ID_CONFIG, "execution-test-group")
                .containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
                .containsEntry(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 1_800_000)
                .containsEntry(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1);

        assertThat(cfg.kafkaListenerContainerFactory()).isNotNull();
    }

    @Test
    void kafkaListenerContainerFactory_usesConsumerFactory_manualAck_errorHandler_andConcurrency() {
        KafkaConfig cfg = newKafkaConfigWithAllConsumerFields();
        ReflectionTestUtils.setField(cfg, "consumerConcurrency", 5);

        ConcurrentKafkaListenerContainerFactory<String, TestTaskEvent> factory = cfg.kafkaListenerContainerFactory();
        assertThat(factory).isNotNull();
        assertThat(ReflectionTestUtils.getField(factory, "concurrency")).isEqualTo(5);
        assertThat(factory.getConsumerFactory()).isNotNull();
        assertThat(factory.getConsumerFactory())
                .isInstanceOfSatisfying(DefaultKafkaConsumerFactory.class, cf ->
                        assertThat(cf.getConfigurationProperties())
                                .containsEntry(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092"));
        assertThat(factory.getContainerProperties().getAckMode()).isEqualTo(ContainerProperties.AckMode.MANUAL);
        assertThat(ReflectionTestUtils.getField(factory, "commonErrorHandler")).isInstanceOf(DefaultErrorHandler.class);
    }
}
