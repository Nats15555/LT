package com.loadtest.app.service;

import com.loadtest.app.dto.CreateDockerProfileRequest;
import com.loadtest.app.dto.UpdateDockerProfileRequest;
import com.loadtest.app.persistence.DockerExecutionProfileEntity;
import com.loadtest.app.persistence.DockerExecutionProfileRepository;
import com.loadtest.app.persistence.TestTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DockerExecutionProfileServiceTest {

    @Mock
    private DockerExecutionProfileRepository repository;
    @Mock
    private TestTaskRepository testTaskRepository;
    @Mock
    private QueuePauseService queuePauseService;

    private DockerExecutionProfileService service;

    @BeforeEach
    void setUp() {
        service = new DockerExecutionProfileService(repository, testTaskRepository, queuePauseService);
    }

    @Test
    void resolveProfileIdForUpload_parsesUuidAndChecksEnabled() {
        UUID id = UUID.randomUUID();
        DockerExecutionProfileEntity e = DockerExecutionProfileEntity.builder().id(id).enabled(true).build();
        when(repository.findById(id)).thenReturn(Optional.of(e));
        assertThat(service.resolveProfileIdForUpload("  " + id + "  ")).isEqualTo(id);
    }

    @Test
    void resolveProfileIdForUpload_invalidUuid() {
        assertThatThrownBy(() -> service.resolveProfileIdForUpload("not-a-uuid"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid dockerExecutionProfileId");
    }

    @Test
    void resolveProfileIdForUpload_profileIdNotInDb() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.resolveProfileIdForUpload(id.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void resolveProfileIdForUpload_disabledProfile() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(
                DockerExecutionProfileEntity.builder().id(id).enabled(false).build()));
        assertThatThrownBy(() -> service.resolveProfileIdForUpload(id.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("disabled");
    }

    @Test
    void resolveProfileIdForUpload_defaultFromNameThenAny() {
        UUID defId = UUID.randomUUID();
        when(repository.findFirstByNameAndEnabledTrue(DockerExecutionProfileService.DEFAULT_PROFILE_NAME))
                .thenReturn(Optional.of(DockerExecutionProfileEntity.builder().id(defId).build()));
        assertThat(service.resolveProfileIdForUpload(null)).isEqualTo(defId);
    }

    @Test
    void resolveProfileIdForUpload_fallbackEnabled() {
        UUID anyId = UUID.randomUUID();
        when(repository.findFirstByNameAndEnabledTrue(DockerExecutionProfileService.DEFAULT_PROFILE_NAME))
                .thenReturn(Optional.empty());
        when(repository.findFirstByEnabledTrueOrderByCreatedAtAsc())
                .thenReturn(Optional.of(DockerExecutionProfileEntity.builder().id(anyId).build()));
        assertThat(service.resolveProfileIdForUpload("")).isEqualTo(anyId);
    }

    @Test
    void resolveProfileIdForUpload_noProfiles() {
        when(repository.findFirstByNameAndEnabledTrue(DockerExecutionProfileService.DEFAULT_PROFILE_NAME))
                .thenReturn(Optional.empty());
        when(repository.findFirstByEnabledTrueOrderByCreatedAtAsc()).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.resolveProfileIdForUpload(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No docker execution profile");
    }

    @Test
    void listProfiles_mapsEntities() {
        UUID id = UUID.randomUUID();
        DockerExecutionProfileEntity row = DockerExecutionProfileEntity.builder()
                .id(id)
                .name("p1")
                .enabled(true)
                .maxConcurrentContainers(1)
                .createdAt(OffsetDateTime.MIN)
                .updatedAt(OffsetDateTime.MIN)
                .build();
        when(repository.findAllByOrderByNameAsc()).thenReturn(List.of(row));
        assertThat(service.listProfiles()).singleElement().satisfies(d -> assertThat(d.getName()).isEqualTo("p1"));
    }

    @Test
    void listEnabledProfiles_filters() {
        UUID id = UUID.randomUUID();
        DockerExecutionProfileEntity row = DockerExecutionProfileEntity.builder()
                .id(id)
                .name("e1")
                .enabled(true)
                .maxConcurrentContainers(1)
                .createdAt(OffsetDateTime.MIN)
                .updatedAt(OffsetDateTime.MIN)
                .build();
        when(repository.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of(row));
        assertThat(service.listEnabledProfiles()).hasSize(1);
    }

    @Test
    void getById_returnsDto() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(
                DockerExecutionProfileEntity.builder()
                        .id(id)
                        .name("n")
                        .enabled(true)
                        .maxConcurrentContainers(1)
                        .createdAt(OffsetDateTime.MIN)
                        .updatedAt(OffsetDateTime.MIN)
                        .build()));
        assertThat(service.getById(id).getId()).isEqualTo(id);
    }

    @Test
    void getById_notFound() {
        when(repository.findById(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getById(UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Profile not found");
    }

    @Test
    void create_rejectsWhenQueueNotPausedAndTasksExist() {
        when(queuePauseService.isQueuePaused()).thenReturn(false);
        when(testTaskRepository.count()).thenReturn(1L);
        CreateDockerProfileRequest req = CreateDockerProfileRequest.builder().name("n").build();
        assertThatThrownBy(() -> service.create(req)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("очереди");
        verify(repository, never()).save(any());
    }

    @Test
    void create_savesNewProfile() {
        when(queuePauseService.isQueuePaused()).thenReturn(true);
        when(repository.existsByName("new")).thenReturn(false);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CreateDockerProfileRequest req = CreateDockerProfileRequest.builder()
                .name("  new  ")
                .enabled(true)
                .build();
        assertThat(service.create(req).getName()).isEqualTo("new");
        ArgumentCaptor<DockerExecutionProfileEntity> cap = ArgumentCaptor.forClass(DockerExecutionProfileEntity.class);
        verify(repository).save(cap.capture());
        assertThat(cap.getValue().getMaxConcurrentContainers()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void create_appliesResourceDefaultsWhenNulls() {
        when(queuePauseService.isQueuePaused()).thenReturn(true);
        when(repository.existsByName("new")).thenReturn(false);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CreateDockerProfileRequest req = CreateDockerProfileRequest.builder()
                .name("new")
                .enabled(true)
                .build();
        var dto = service.create(req);
        assertThat(dto.getMemoryLimitMb()).isEqualTo(512);
        assertThat(dto.getMemoryReservationMb()).isEqualTo(256);
        assertThat(dto.getCpuLimit()).isEqualByComparingTo("0.5");
        assertThat(dto.getCpuShares()).isEqualTo(512);
        assertThat(dto.getMaxConcurrentContainers()).isEqualTo(1);
        assertThat(dto.getNetworkMode()).isEqualTo("loadtest_loadtest-network");
        assertThat(dto.getRestartPolicy()).isEqualTo("no");
        assertThat(dto.getLogDriver()).isEqualTo("json-file");
        assertThat(dto.getLogMaxSize()).isEqualTo("10m");
        assertThat(dto.getLogMaxFiles()).isEqualTo(3);
    }

    @Test
    void create_rejectsBlankNameAfterTrim() {
        when(queuePauseService.isQueuePaused()).thenReturn(true);
        assertThatThrownBy(() -> service.create(CreateDockerProfileRequest.builder().name("   ").build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void create_allowedWhenQueueNotPausedButNoTasks() {
        when(queuePauseService.isQueuePaused()).thenReturn(false);
        when(testTaskRepository.count()).thenReturn(0L);
        when(repository.existsByName("solo")).thenReturn(false);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        assertThat(service.create(CreateDockerProfileRequest.builder().name("solo").build()).getName()).isEqualTo("solo");
    }

    @Test
    void create_rejectsDuplicateName() {
        when(queuePauseService.isQueuePaused()).thenReturn(true);
        when(repository.existsByName("dup")).thenReturn(true);
        assertThatThrownBy(() -> service.create(CreateDockerProfileRequest.builder().name("dup").build()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void update_appliesPartialFields() {
        when(queuePauseService.isQueuePaused()).thenReturn(true);
        UUID id = UUID.randomUUID();
        DockerExecutionProfileEntity e = DockerExecutionProfileEntity.builder()
                .id(id)
                .name("old")
                .enabled(true)
                .createdAt(OffsetDateTime.MIN)
                .updatedAt(OffsetDateTime.MIN)
                .maxConcurrentContainers(1)
                .build();
        when(repository.findById(id)).thenReturn(Optional.of(e));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateDockerProfileRequest upd = new UpdateDockerProfileRequest();
        upd.setMemoryLimitMb(999);
        assertThat(service.update(id, upd).getMemoryLimitMb()).isEqualTo(999);
    }

    @Test
    void delete_rejectsDefaultName() {
        when(queuePauseService.isQueuePaused()).thenReturn(true);
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(
                DockerExecutionProfileEntity.builder().id(id).name(DockerExecutionProfileService.DEFAULT_PROFILE_NAME).build()));
        assertThatThrownBy(() -> service.delete(id)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("системный");
    }

    @Test
    void delete_rejectsWhenTasksQueuedForProfile() {
        when(queuePauseService.isQueuePaused()).thenReturn(true);
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(
                DockerExecutionProfileEntity.builder().id(id).name("x").build()));
        when(testTaskRepository.countByDockerExecutionProfileId(id)).thenReturn(1L);
        assertThatThrownBy(() -> service.delete(id)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("очереди");
    }

    @Test
    void delete_ok() {
        when(queuePauseService.isQueuePaused()).thenReturn(true);
        UUID id = UUID.randomUUID();
        DockerExecutionProfileEntity e = DockerExecutionProfileEntity.builder().id(id).name("rem").build();
        when(repository.findById(id)).thenReturn(Optional.of(e));
        when(testTaskRepository.countByDockerExecutionProfileId(id)).thenReturn(0L);
        service.delete(id);
        verify(repository).delete(e);
    }

    @Test
    void update_maxConcurrentClampedToAtLeastOne() {
        when(queuePauseService.isQueuePaused()).thenReturn(true);
        UUID id = UUID.randomUUID();
        DockerExecutionProfileEntity e = DockerExecutionProfileEntity.builder()
                .id(id)
                .name("n")
                .enabled(true)
                .createdAt(OffsetDateTime.MIN)
                .updatedAt(OffsetDateTime.MIN)
                .maxConcurrentContainers(2)
                .build();
        when(repository.findById(id)).thenReturn(Optional.of(e));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        UpdateDockerProfileRequest upd = new UpdateDockerProfileRequest();
        upd.setMaxConcurrentContainers(0);
        assertThat(service.update(id, upd).getMaxConcurrentContainers()).isEqualTo(1);
    }

    @Test
    void update_notFound() {
        when(queuePauseService.isQueuePaused()).thenReturn(true);
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.update(id, new UpdateDockerProfileRequest()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Profile not found");
    }

    @Test
    void delete_notFound() {
        when(queuePauseService.isQueuePaused()).thenReturn(true);
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.delete(id)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void update_rejectsDuplicateRename() {
        when(queuePauseService.isQueuePaused()).thenReturn(true);
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(
                DockerExecutionProfileEntity.builder().id(id).name("a").build()));
        when(repository.existsByName("b")).thenReturn(true);
        UpdateDockerProfileRequest upd = new UpdateDockerProfileRequest();
        upd.setName("b");
        assertThatThrownBy(() -> service.update(id, upd)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void update_appliesAllOptionalFields() {
        when(queuePauseService.isQueuePaused()).thenReturn(true);
        UUID id = UUID.randomUUID();
        DockerExecutionProfileEntity e = DockerExecutionProfileEntity.builder()
                .id(id)
                .name("old")
                .enabled(true)
                .createdAt(OffsetDateTime.MIN)
                .updatedAt(OffsetDateTime.MIN)
                .maxConcurrentContainers(1)
                .build();
        when(repository.findById(id)).thenReturn(Optional.of(e));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateDockerProfileRequest upd = new UpdateDockerProfileRequest();
        upd.setName("new");
        upd.setDockerHostUri(" tcp://x ");
        upd.setNamedVolumeForChildBinds(" vol ");
        upd.setMemoryLimitMb(512);
        upd.setMemoryReservationMb(256);
        upd.setCpuLimit(BigDecimal.valueOf(0.9));
        upd.setCpuShares(1024);
        upd.setMaxConcurrentContainers(3);
        upd.setNetworkMode("host");
        upd.setRestartPolicy("always");
        upd.setRestartMaxRetries(4);
        upd.setLogDriver("json-file");
        upd.setLogMaxSize("20m");
        upd.setLogMaxFiles(10);
        upd.setEnvironmentVariables("{\"A\":\"B\"}");
        upd.setLabels("{\"L\":\"1\"}");
        upd.setEnabled(false);

        var dto = service.update(id, upd);
        assertThat(dto.getName()).isEqualTo("new");
        assertThat(dto.getDockerHostUri()).isEqualTo("tcp://x");
        assertThat(dto.getNamedVolumeForChildBinds()).isEqualTo("vol");
        assertThat(dto.getMemoryLimitMb()).isEqualTo(512);
        assertThat(dto.getMemoryReservationMb()).isEqualTo(256);
        assertThat(dto.getCpuLimit()).isEqualByComparingTo("0.9");
        assertThat(dto.getCpuShares()).isEqualTo(1024);
        assertThat(dto.getMaxConcurrentContainers()).isEqualTo(3);
        assertThat(dto.getNetworkMode()).isEqualTo("host");
        assertThat(dto.getRestartPolicy()).isEqualTo("always");
        assertThat(dto.getRestartMaxRetries()).isEqualTo(4);
        assertThat(dto.getLogDriver()).isEqualTo("json-file");
        assertThat(dto.getLogMaxSize()).isEqualTo("20m");
        assertThat(dto.getLogMaxFiles()).isEqualTo(10);
        assertThat(dto.getEnvironmentVariables()).contains("A");
        assertThat(dto.getLabels()).contains("L");
        assertThat(dto.isEnabled()).isFalse();
    }

    @Test
    void update_sameNameAndBlankOptionals_trimToNullBranches() {
        when(queuePauseService.isQueuePaused()).thenReturn(true);
        UUID id = UUID.randomUUID();
        DockerExecutionProfileEntity e = DockerExecutionProfileEntity.builder()
                .id(id)
                .name("same")
                .enabled(true)
                .dockerHostUri("tcp://old")
                .namedVolumeForChildBinds("vol-old")
                .createdAt(OffsetDateTime.MIN)
                .updatedAt(OffsetDateTime.MIN)
                .maxConcurrentContainers(1)
                .build();
        when(repository.findById(id)).thenReturn(Optional.of(e));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateDockerProfileRequest upd = new UpdateDockerProfileRequest();
        upd.setName(" same ");
        upd.setDockerHostUri("   ");
        upd.setNamedVolumeForChildBinds("   ");

        var dto = service.update(id, upd);
        assertThat(dto.getName()).isEqualTo("same");
        assertThat(dto.getDockerHostUri()).isNull();
        assertThat(dto.getNamedVolumeForChildBinds()).isNull();
    }

    @Test
    void create_enabledNullDefaultsTrue() {
        when(queuePauseService.isQueuePaused()).thenReturn(true);
        when(repository.existsByName("n")).thenReturn(false);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        CreateDockerProfileRequest req = CreateDockerProfileRequest.builder()
                .name("n")
                .enabled(null)
                .build();
        assertThat(service.create(req).isEnabled()).isTrue();
    }

    @Test
    void trimToNull_helperBranches() {
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "trimToNull", new Object[]{null})).isNull();
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "trimToNull", "   ")).isNull();
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "trimToNull", " x ")).isEqualTo("x");
    }

    @Test
    void create_withAllResourceValues_hitsNonDefaultBranches() {
        when(queuePauseService.isQueuePaused()).thenReturn(true);
        when(repository.existsByName("full")).thenReturn(false);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CreateDockerProfileRequest req = CreateDockerProfileRequest.builder()
                .name("full")
                .memoryLimitMb(2048)
                .memoryReservationMb(1024)
                .cpuLimit(new BigDecimal("1.5"))
                .cpuShares(2048)
                .maxConcurrentContainers(5)
                .networkMode("host")
                .restartPolicy("always")
                .restartMaxRetries(9)
                .logDriver("json-file")
                .logMaxSize("100m")
                .logMaxFiles(8)
                .environmentVariables("{\"A\":\"1\"}")
                .labels("{\"L\":\"x\"}")
                .enabled(true)
                .build();
        var dto = service.create(req);
        assertThat(dto.getMemoryLimitMb()).isEqualTo(2048);
        assertThat(dto.getMemoryReservationMb()).isEqualTo(1024);
        assertThat(dto.getCpuLimit()).isEqualByComparingTo("1.5");
        assertThat(dto.getCpuShares()).isEqualTo(2048);
        assertThat(dto.getMaxConcurrentContainers()).isEqualTo(5);
        assertThat(dto.getNetworkMode()).isEqualTo("host");
        assertThat(dto.getRestartPolicy()).isEqualTo("always");
        assertThat(dto.getRestartMaxRetries()).isEqualTo(9);
        assertThat(dto.getLogMaxSize()).isEqualTo("100m");
        assertThat(dto.getLogMaxFiles()).isEqualTo(8);
    }
}
