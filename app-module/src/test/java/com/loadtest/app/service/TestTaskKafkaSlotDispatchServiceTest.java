package com.loadtest.app.service;

import com.loadtest.app.dto.TestTaskEvent;
import com.loadtest.app.persistence.TestTaskKafkaPendingRepository;
import com.loadtest.app.util.DatabaseAvailabilityService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TestTaskKafkaSlotDispatchServiceTest {

    @Mock private QueuePauseService queuePauseService;
    @Mock private KafkaOutboxService kafkaOutboxService;
    @Mock private TestTaskKafkaPendingRepository pendingRepository;
    @Mock private DatabaseAvailabilityService databaseAvailabilityService;
    @Mock private EntityManager entityManager;
    @Mock private Query nativeQuery;

    private TestTaskKafkaSlotDispatchService service;

    @BeforeEach
    void setUp() {
        service = new TestTaskKafkaSlotDispatchService(
                queuePauseService, kafkaOutboxService, pendingRepository, databaseAvailabilityService, entityManager);
        ReflectionTestUtils.setField(service, "batchSize", 32);
        ReflectionTestUtils.setField(service, "kafkaInflightTimeoutSeconds", 90);
    }

    @Test
    void dispatchAvailableSlots_skipsKafkaWhenQueuePausedButStillRecovers() {
        when(databaseAvailabilityService.isAvailable()).thenReturn(true);
        when(queuePauseService.isQueuePaused()).thenReturn(true);
        when(entityManager.createNativeQuery(anyString())).thenReturn(nativeQuery);
        when(nativeQuery.setParameter(anyString(), any())).thenReturn(nativeQuery);
        when(nativeQuery.executeUpdate()).thenReturn(0);

        service.dispatchAvailableSlots();

        verify(entityManager, times(1)).createNativeQuery(anyString());
        verify(kafkaOutboxService, never()).sendTestTaskEvent(anyString(), any());
    }

    @Test
    void dispatchAvailableSlots_sendsKafkaAndRemovesPending() {
        UUID taskId = UUID.randomUUID();
        when(databaseAvailabilityService.isAvailable()).thenReturn(true);
        when(queuePauseService.isQueuePaused()).thenReturn(false);
        when(entityManager.createNativeQuery(anyString())).thenReturn(nativeQuery);
        when(nativeQuery.setParameter(anyString(), any())).thenReturn(nativeQuery);
        when(nativeQuery.executeUpdate()).thenReturn(0);
        when(nativeQuery.getSingleResult()).thenReturn(1L);
        when(nativeQuery.getResultList()).thenReturn(List.of(taskId));

        service.dispatchAvailableSlots();

        verify(kafkaOutboxService).sendTestTaskEvent(eq(taskId.toString()), any(TestTaskEvent.class));
        verify(pendingRepository).deleteById(taskId);
    }

    @Test
    void dispatchAvailableSlots_keepsPendingWhenKafkaFails() {
        UUID taskId = UUID.randomUUID();
        when(databaseAvailabilityService.isAvailable()).thenReturn(true);
        when(queuePauseService.isQueuePaused()).thenReturn(false);
        when(entityManager.createNativeQuery(anyString())).thenReturn(nativeQuery);
        when(nativeQuery.setParameter(anyString(), any())).thenReturn(nativeQuery);
        when(nativeQuery.executeUpdate()).thenReturn(0);
        when(nativeQuery.getSingleResult()).thenReturn(1L);
        when(nativeQuery.getResultList()).thenReturn(List.of(taskId));
        doThrow(new RuntimeException("kafka down")).when(kafkaOutboxService).sendTestTaskEvent(anyString(), any());

        service.dispatchAvailableSlots();

        verify(pendingRepository, never()).deleteById(taskId);
    }

    @Test
    void dispatchAvailableSlots_skipsWhenDatabaseUnavailable() {
        when(databaseAvailabilityService.isAvailable()).thenReturn(false);

        service.dispatchAvailableSlots();

        verify(entityManager, never()).createNativeQuery(anyString());
        verify(kafkaOutboxService, never()).sendTestTaskEvent(anyString(), any());
    }
}
