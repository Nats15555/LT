package com.loadtest.app.service;

import com.loadtest.app.dto.TestTaskEvent;
import com.loadtest.app.persistence.TestTaskKafkaPendingRepository;
import com.loadtest.app.util.SlotDispatchNativeQueryParams;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TestTaskKafkaSlotDispatchService {

    private static final long DISPATCH_ADVISORY_LOCK_KEY = 847_291L;

    private static final String RECOVER_STALE_PENDING = """
            INSERT INTO test_task_kafka_pending (task_id, created_at)
            SELECT t.id, CURRENT_TIMESTAMP
            FROM test_task t
            WHERE t.status = 'PENDING'
              AND NOT EXISTS (
                  SELECT 1 FROM test_task_kafka_pending p WHERE p.task_id = t.id
              )
              AND t.updated_at <= :%s
            ON CONFLICT (task_id) DO NOTHING
            """.formatted(SlotDispatchNativeQueryParams.INFLIGHT_CUTOFF);

    private static final String MARK_DISPATCHED = """
            UPDATE test_task
            SET updated_at = :%s
            WHERE id = :%s AND status = 'PENDING'
            """.formatted(SlotDispatchNativeQueryParams.NOW, SlotDispatchNativeQueryParams.TASK_ID);

    private static final String FIND_DISPATCHABLE_TASK_IDS = """
            WITH profile_usage AS (
                SELECT p.id AS profile_id,
                       p.max_concurrent_containers AS cap,
                       COALESCE((
                           SELECT COUNT(*)
                           FROM test_task x
                           WHERE x.docker_execution_profile_id = p.id
                             AND x.status = 'PROCESSING'
                       ), 0)
                       + COALESCE((
                           SELECT COUNT(*)
                           FROM test_task x
                           WHERE x.docker_execution_profile_id = p.id
                             AND x.status = 'PENDING'
                             AND NOT EXISTS (
                                 SELECT 1 FROM test_task_kafka_pending pk2 WHERE pk2.task_id = x.id
                             )
                       ), 0) AS used
                FROM docker_execution_profile p
                WHERE p.enabled = TRUE
            ),
            ranked AS (
                SELECT pk.task_id,
                       pk.created_at,
                       ROW_NUMBER() OVER (
                           PARTITION BY t.docker_execution_profile_id
                           ORDER BY pk.created_at
                       ) AS rn,
                       u.cap - u.used AS free_slots
                FROM test_task_kafka_pending pk
                INNER JOIN test_task t ON t.id = pk.task_id AND t.status = 'PENDING'
                INNER JOIN profile_usage u ON u.profile_id = t.docker_execution_profile_id
                WHERE u.cap > u.used
            )
            SELECT task_id
            FROM ranked
            WHERE rn <= free_slots
            ORDER BY created_at
            LIMIT :%s
            """.formatted(SlotDispatchNativeQueryParams.BATCH_SIZE);

    private final QueuePauseService queuePauseService;
    private final KafkaOutboxService kafkaOutboxService;
    private final TestTaskKafkaPendingRepository pendingRepository;
    private final EntityManager entityManager;

    @Value("${loadtest.queue.slot-dispatch-batch-size:32}")
    private int batchSize;

    @Value("${loadtest.queue.kafka-inflight-timeout-seconds:90}")
    private int kafkaInflightTimeoutSeconds;

    @Scheduled(fixedDelayString = "${loadtest.queue.slot-dispatch-interval-ms:1500}")
    @Transactional
    public void scheduledDispatch() {
        dispatchAvailableSlots();
    }

    @Transactional
    public void dispatchAvailableSlots() {
        recoverStalePendingTasks();
        if (queuePauseService.isQueuePaused()) {
            return;
        }
        acquireDispatchLock();
        for (UUID taskId : findDispatchableTaskIds()) {
            dispatchTask(taskId);
        }
    }

    private void recoverStalePendingTasks() {
        OffsetDateTime inflightCutoff = inflightCutoff();
        int recovered = entityManager.createNativeQuery(RECOVER_STALE_PENDING)
                .setParameter(SlotDispatchNativeQueryParams.INFLIGHT_CUTOFF, inflightCutoff)
                .executeUpdate();
        if (recovered > 0) {
            log.warn("Re-queued {} stale PENDING task(s) for Kafka dispatch (inflight timeout {}s exceeded)",
                    recovered, kafkaInflightTimeoutSeconds);
        }
    }

    private void acquireDispatchLock() {
        entityManager.createNativeQuery(
                        "SELECT pg_advisory_xact_lock(:%s)".formatted(SlotDispatchNativeQueryParams.LOCK_KEY))
                .setParameter(SlotDispatchNativeQueryParams.LOCK_KEY, DISPATCH_ADVISORY_LOCK_KEY)
                .getSingleResult();
    }

    private OffsetDateTime inflightCutoff() {
        return OffsetDateTime.now().minusSeconds(kafkaInflightTimeoutSeconds);
    }

    private List<UUID> findDispatchableTaskIds() {
        @SuppressWarnings("unchecked")
        List<Object> rows = entityManager.createNativeQuery(FIND_DISPATCHABLE_TASK_IDS)
                .setParameter(SlotDispatchNativeQueryParams.BATCH_SIZE, batchSize)
                .getResultList();
        List<UUID> ids = new ArrayList<>(rows.size());
        for (Object row : rows) {
            ids.add(toUuid(row));
        }
        return ids;
    }

    private void dispatchTask(UUID taskId) {
        String taskIdStr = taskId.toString();
        try {
            kafkaOutboxService.sendTestTaskEvent(taskIdStr, new TestTaskEvent(taskIdStr));
            OffsetDateTime now = OffsetDateTime.now();
            entityManager.createNativeQuery(MARK_DISPATCHED)
                    .setParameter(SlotDispatchNativeQueryParams.NOW, now)
                    .setParameter(SlotDispatchNativeQueryParams.TASK_ID, taskId)
                    .executeUpdate();
            pendingRepository.deleteById(taskId);
            log.info("Dispatched test task {} to Kafka (docker profile slot available)", taskIdStr);
        } catch (RuntimeException e) {
            log.error("Failed to dispatch test task {} to Kafka; will retry on next scheduler tick", taskIdStr, e);
        }
    }

    private static UUID toUuid(Object row) {
        if (row instanceof UUID uuid) {
            return uuid;
        }
        return UUID.fromString(row.toString());
    }
}
