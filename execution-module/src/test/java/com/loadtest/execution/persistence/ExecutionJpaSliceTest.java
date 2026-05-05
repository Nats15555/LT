package com.loadtest.execution.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@TestPropertySource(locations = "classpath:application-test.properties")
class ExecutionJpaSliceTest {

    @Autowired private TestTaskRepository testTaskRepository;
    @Autowired private DockerExecutionProfileRepository dockerExecutionProfileRepository;
    @Autowired private LoadTestToolRepository loadTestToolRepository;

    @Test
    void testTask_roundTrip() {
        UUID id = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        TestTaskEntity e = TestTaskEntity.builder()
                .id(id)
                .status(TestTaskStatus.PENDING)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .testTool("k6")
                .testFileName("t.js")
                .testFileContentBase64("YQ==")
                .command("run")
                .dockerExecutionProfileId(profileId)
                .build();
        testTaskRepository.save(e);
        Optional<TestTaskEntity> loaded = testTaskRepository.findById(id);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getTestTool()).isEqualTo("k6");
    }

    @Test
    void dockerProfile_findByIdAndEnabledTrue() {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        DockerExecutionProfileEntity p = DockerExecutionProfileEntity.builder()
                .id(id)
                .name("p1")
                .enabled(true)
                .maxConcurrentContainers(2)
                .createdAt(now)
                .updatedAt(now)
                .build();
        dockerExecutionProfileRepository.save(p);
        assertThat(dockerExecutionProfileRepository.findByIdAndEnabledTrue(id)).isPresent();
    }

    @Test
    void loadTestTool_findByNameAndEnabledTrue() {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        LoadTestToolEntity t = LoadTestToolEntity.builder()
                .id(id)
                .name("tool-jpa-" + id.toString().substring(0, 8))
                .dockerImage("alpine:latest")
                .fileExtensions(java.util.List.of("js"))
                .enabled(true)
                .createdAt(now)
                .updatedAt(now)
                .build();
        loadTestToolRepository.save(t);
        assertThat(loadTestToolRepository.findByNameAndEnabledTrue(t.getName())).isPresent();
    }
}
