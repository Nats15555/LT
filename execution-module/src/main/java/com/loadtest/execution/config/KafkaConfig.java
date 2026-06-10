package com.loadtest.execution.config;

import com.loadtest.execution.dto.MetricsCollectionEvent;
import com.loadtest.execution.dto.TestTaskEvent;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.Map;

@Configuration
@EnableKafka
public class KafkaConfig {

    @Value("${kafka.topic.test-tasks:test-tasks}")
    private String testTasksTopicName;

    @Value("${kafka.topic.metrics-collection-tasks:metrics-collection-tasks}")
    private String metricsCollectionTasksTopicName;

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id:execution-service-group}")
    private String groupId;

    @Value("${spring.kafka.consumer.auto-offset-reset:earliest}")
    private String autoOffsetReset;

    @Value("${spring.kafka.consumer.properties.max.poll.interval.ms:1800000}")
    private Integer maxPollIntervalMs;

    @Value("${spring.kafka.consumer.max-poll-records:1}")
    private Integer maxPollRecords;

    @Value("${loadtest.execution.consumer-concurrency:3}")
    private Integer consumerConcurrency;

    @Bean
    public NewTopic testTasksTopic() {
        return TopicBuilder.name(testTasksTopicName)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic metricsCollectionTasksTopic() {
        return TopicBuilder.name(metricsCollectionTasksTopicName)
                .partitions(1)
                .replicas(1)
                .build();
    }

    @Bean
    public ProducerFactory<String, MetricsCollectionEvent> metricsCollectionProducerFactory() {
        Map<String, Object> props = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class,
                JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, MetricsCollectionEvent> metricsCollectionKafkaTemplate() {
        return new KafkaTemplate<>(metricsCollectionProducerFactory());
    }

    @Bean
    public ConsumerFactory<String, TestTaskEvent> consumerFactory() {
        Map<String, Object> props = Map.ofEntries(
                Map.entry(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers),
                Map.entry(ConsumerConfig.GROUP_ID_CONFIG, groupId),
                Map.entry(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class),
                Map.entry(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class),
                Map.entry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset),
                Map.entry(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, maxPollIntervalMs),
                Map.entry(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, maxPollRecords),
                Map.entry(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class),
                Map.entry(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class.getName()),
                Map.entry(JsonDeserializer.VALUE_DEFAULT_TYPE, TestTaskEvent.class.getName()),
                Map.entry(JsonDeserializer.TRUSTED_PACKAGES, "*"),
                Map.entry(JsonDeserializer.USE_TYPE_INFO_HEADERS, false));

        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, TestTaskEvent> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, TestTaskEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.setConcurrency(consumerConcurrency);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(new FixedBackOff(1000L, 2L));
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }

}
