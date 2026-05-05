package com.loadtest.summarization.config;

import com.loadtest.summarization.dto.SummarizationTaskEvent;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaConfigTest {

    @Test
    void consumerFactory_andListenerFactory_areCreated() {
        KafkaConfig cfg = new KafkaConfig();
        ReflectionTestUtils.setField(cfg, "bootstrapServers", "localhost:9092");
        ReflectionTestUtils.setField(cfg, "groupId", "summarization-test-group");

        ConsumerFactory<String, SummarizationTaskEvent> factory = cfg.consumerFactory();
        assertThat(factory).isInstanceOf(DefaultKafkaConsumerFactory.class);
        assertThat(((DefaultKafkaConsumerFactory<String, SummarizationTaskEvent>) factory).getConfigurationProperties())
                .containsEntry("bootstrap.servers", "localhost:9092")
                .containsEntry("group.id", "summarization-test-group");

        assertThat(cfg.kafkaListenerContainerFactory()).isNotNull();
    }
}
