package com.loadtest.app.service;

import com.loadtest.app.dto.TestTaskEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueuePauseServiceTest {

    @Mock
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    @Mock
    private KafkaOutboxService kafkaOutboxService;

    private QueuePauseService service;

    @BeforeEach
    void setUp() {
        service = new QueuePauseService(jdbcTemplate, kafkaOutboxService);
    }

    @Test
    void ensureSchema_runsDdl() {
        service.ensureSchema();
        verify(jdbcTemplate, times(4)).execute(anyString());
    }

    @Test
    void isQueuePaused_readsFlag() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class))).thenReturn(true);
        assertThat(service.isQueuePaused()).isTrue();
    }

    @Test
    void isQueuePaused_onErrorReturnsFalse() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class))).thenThrow(new RuntimeException("db"));
        assertThat(service.isQueuePaused()).isFalse();
    }

    @Test
    void countPendingKafkaDispatches_handlesError() {
        when(jdbcTemplate.queryForObject(contains("COUNT"), eq(Long.class))).thenThrow(new RuntimeException());
        assertThat(service.countPendingKafkaDispatches()).isZero();
    }

    @Test
    void countPendingKafkaDispatches_returnsCount() {
        when(jdbcTemplate.queryForObject(contains("COUNT"), eq(Long.class))).thenReturn(5L);
        assertThat(service.countPendingKafkaDispatches()).isEqualTo(5L);
    }

    @Test
    void recordPendingKafkaDispatch_inserts() {
        UUID id = UUID.randomUUID();
        service.recordPendingKafkaDispatch(id);
        verify(jdbcTemplate).update(contains("test_task_kafka_pending"), eq(id));
    }

    @Test
    void setPaused_whenResuming_drainsPending() {
        UUID taskId = UUID.randomUUID();
        when(jdbcTemplate.query(contains("test_task_kafka_pending"), any(RowMapper.class)))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    RowMapper<UUID> mapper = inv.getArgument(1);
                    ResultSet rs = org.mockito.Mockito.mock(ResultSet.class);
                    when(rs.getObject("task_id", UUID.class)).thenReturn(taskId);
                    return List.of(mapper.mapRow(rs, 0));
                })
                .thenReturn(List.of());
        QueuePauseService.QueuePauseState st = service.setPaused(false);
        verify(kafkaOutboxService).sendTestTaskEvent(eq(taskId.toString()), any(TestTaskEvent.class));
        verify(jdbcTemplate).update(eq("DELETE FROM test_task_kafka_pending WHERE task_id = ?"), eq(taskId));
        assertThat(st).isNotNull();
    }

    @Test
    void setPaused_trueDoesNotDrain() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class))).thenReturn(true);
        when(jdbcTemplate.queryForObject(contains("COUNT"), eq(Long.class))).thenReturn(0L);
        QueuePauseService.QueuePauseState st = service.setPaused(true);
        assertThat(st.paused()).isTrue();
        verify(jdbcTemplate, never()).query(contains("test_task_kafka_pending"), any(RowMapper.class));
    }

    @Test
    void setPaused_drainStopsWhenKafkaFails() {
        UUID taskId = UUID.randomUUID();
        when(jdbcTemplate.query(contains("test_task_kafka_pending"), any(RowMapper.class)))
                .thenReturn(List.of(taskId));
        doThrow(new RuntimeException("send failed")).when(kafkaOutboxService)
                .sendTestTaskEvent(anyString(), any(TestTaskEvent.class));
        service.setPaused(false);
        verify(jdbcTemplate, never()).update(eq("DELETE FROM test_task_kafka_pending WHERE task_id = ?"), eq(taskId));
    }

    @Test
    void getState_reflectsJdbc() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class))).thenReturn(true);
        when(jdbcTemplate.queryForObject(contains("COUNT"), eq(Long.class))).thenReturn(3L);
        QueuePauseService.QueuePauseState st = service.getState();
        assertThat(st.paused()).isTrue();
        assertThat(st.pendingKafkaDispatchCount()).isEqualTo(3L);
    }
}
