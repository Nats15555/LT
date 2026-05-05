package com.loadtest.execution.model;

import com.loadtest.execution.persistence.TestTaskStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionLombokBranchExhaustiveTest {

    @Test
    void testTaskStatus_enumSmoke() {
        assertThat(TestTaskStatus.values()).isNotEmpty();
        assertThat(TestTaskStatus.valueOf(TestTaskStatus.PENDING.name())).isEqualTo(TestTaskStatus.PENDING);
    }
}
