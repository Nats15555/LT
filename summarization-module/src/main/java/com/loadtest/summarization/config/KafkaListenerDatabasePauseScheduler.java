package com.loadtest.summarization.config;

import com.loadtest.summarization.util.DatabaseAvailabilityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaListenerDatabasePauseScheduler {

    private final DatabaseAvailabilityService databaseAvailabilityService;
    private final ApplicationContext applicationContext;

    private volatile boolean paused;

    @Scheduled(fixedDelayString = "${loadtest.database.availability-check-interval-ms:5000}")
    public void syncKafkaListenersWithDatabase() {
        if (databaseAvailabilityService.isAvailable()) {
            if (paused) {
                resumeAll();
                paused = false;
                log.info("PostgreSQL available — Kafka listeners resumed");
            }
            return;
        }
        if (!paused) {
            pauseAll();
            paused = true;
            log.warn("PostgreSQL unavailable — Kafka listeners paused");
        }
    }

    private KafkaListenerEndpointRegistry listenerRegistry() {
        return applicationContext.getBean(KafkaListenerEndpointRegistry.class);
    }

    private void pauseAll() {
        for (MessageListenerContainer container : listenerRegistry().getListenerContainers()) {
            if (container.isRunning() && !container.isContainerPaused()) {
                container.pause();
            }
        }
    }

    private void resumeAll() {
        for (MessageListenerContainer container : listenerRegistry().getListenerContainers()) {
            if (container.isContainerPaused()) {
                container.resume();
            }
        }
    }
}
