package com.loadtest.app.service;

import com.loadtest.app.dto.CreateDockerProfileRequest;
import com.loadtest.app.dto.DockerProfileDto;
import com.loadtest.app.dto.UpdateDockerProfileRequest;
import com.loadtest.app.persistence.DockerExecutionProfileEntity;
import com.loadtest.app.persistence.DockerExecutionProfileRepository;
import com.loadtest.app.persistence.TestTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DockerExecutionProfileService {

    public static final String DEFAULT_PROFILE_NAME = "Default";

    private final DockerExecutionProfileRepository repository;
    private final TestTaskRepository testTaskRepository;
    private final QueuePauseService queuePauseService;

    public UUID resolveProfileIdForUpload(String dockerExecutionProfileIdParam) {
        if (dockerExecutionProfileIdParam != null && !dockerExecutionProfileIdParam.isBlank()) {
            UUID id;
            try {
                id = UUID.fromString(dockerExecutionProfileIdParam.trim());
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("Invalid dockerExecutionProfileId: " + dockerExecutionProfileIdParam);
            }
            DockerExecutionProfileEntity p = repository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("dockerExecutionProfileId not found: " + id));
            if (!p.isEnabled()) {
                throw new IllegalArgumentException("Docker profile is disabled: " + id);
            }
            return id;
        }
        return repository.findFirstByNameAndEnabledTrue(DEFAULT_PROFILE_NAME)
                .or(() -> repository.findFirstByEnabledTrueOrderByCreatedAtAsc())
                .map(DockerExecutionProfileEntity::getId)
                .orElseThrow(() -> new IllegalStateException("No docker execution profile in database"));
    }

    @Transactional(readOnly = true)
    public List<DockerProfileDto> listProfiles() {
        return repository.findAllByOrderByNameAsc().stream().map(this::toDto).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<DockerProfileDto> listEnabledProfiles() {
        return repository.findAllByEnabledTrueOrderByNameAsc().stream().map(this::toDto).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public DockerProfileDto getById(UUID id) {
        return repository.findById(id).map(this::toDto)
                .orElseThrow(() -> new IllegalArgumentException("Profile not found: " + id));
    }

    @Transactional
    public DockerProfileDto create(CreateDockerProfileRequest request) {
        assertNoQueuedTestTasksForDockerMutation();
        String name = request.getName().trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Profile name is required");
        }
        if (repository.existsByName(name)) {
            throw new IllegalStateException("Профиль с именем «" + name + "» уже существует");
        }
        OffsetDateTime now = OffsetDateTime.now();
        DockerExecutionProfileEntity e = DockerExecutionProfileEntity.builder()
                .id(UUID.randomUUID())
                .name(name)
                .dockerHostUri(trimToNull(request.getDockerHostUri()))
                .namedVolumeForChildBinds(trimToNull(request.getNamedVolumeForChildBinds()))
                .enabled(request.getEnabled() == null || Boolean.TRUE.equals(request.getEnabled()))
                .createdAt(now)
                .updatedAt(now)
                .build();
        applyResourceDefaults(request, e);
        return toDto(repository.save(e));
    }

    @Transactional
    public DockerProfileDto update(UUID id, UpdateDockerProfileRequest request) {
        assertNoQueuedTestTasksForDockerMutation();
        DockerExecutionProfileEntity e = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Profile not found: " + id));
        if (request.getName() != null && !request.getName().isBlank()) {
            String newName = request.getName().trim();
            if (!newName.equals(e.getName()) && repository.existsByName(newName)) {
                throw new IllegalStateException("Профиль с именем «" + newName + "» уже существует");
            }
            e.setName(newName);
        }
        if (request.getDockerHostUri() != null) {
            e.setDockerHostUri(trimToNull(request.getDockerHostUri()));
        }
        if (request.getNamedVolumeForChildBinds() != null) {
            e.setNamedVolumeForChildBinds(trimToNull(request.getNamedVolumeForChildBinds()));
        }
        if (request.getMemoryLimitMb() != null) {
            e.setMemoryLimitMb(request.getMemoryLimitMb());
        }
        if (request.getMemoryReservationMb() != null) {
            e.setMemoryReservationMb(request.getMemoryReservationMb());
        }
        if (request.getCpuLimit() != null) {
            e.setCpuLimit(request.getCpuLimit());
        }
        if (request.getCpuShares() != null) {
            e.setCpuShares(request.getCpuShares());
        }
        if (request.getMaxConcurrentContainers() != null) {
            e.setMaxConcurrentContainers(Math.max(1, request.getMaxConcurrentContainers()));
        }
        if (request.getNetworkMode() != null) {
            e.setNetworkMode(request.getNetworkMode());
        }
        if (request.getRestartPolicy() != null) {
            e.setRestartPolicy(request.getRestartPolicy());
        }
        if (request.getRestartMaxRetries() != null) {
            e.setRestartMaxRetries(request.getRestartMaxRetries());
        }
        if (request.getLogDriver() != null) {
            e.setLogDriver(request.getLogDriver());
        }
        if (request.getLogMaxSize() != null) {
            e.setLogMaxSize(request.getLogMaxSize());
        }
        if (request.getLogMaxFiles() != null) {
            e.setLogMaxFiles(request.getLogMaxFiles());
        }
        if (request.getEnvironmentVariables() != null) {
            e.setEnvironmentVariables(request.getEnvironmentVariables());
        }
        if (request.getLabels() != null) {
            e.setLabels(request.getLabels());
        }
        if (request.getEnabled() != null) {
            e.setEnabled(request.getEnabled());
        }
        e.setUpdatedAt(OffsetDateTime.now());
        return toDto(repository.save(e));
    }

    @Transactional
    public void delete(UUID id) {
        assertNoQueuedTestTasksForDockerMutation();
        DockerExecutionProfileEntity e = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Profile not found: " + id));
        if (DEFAULT_PROFILE_NAME.equals(e.getName())) {
            throw new IllegalStateException("Нельзя удалить системный профиль «" + e.getName() + "»");
        }
        long queued = testTaskRepository.countByDockerExecutionProfileId(id);
        if (queued > 0) {
            throw new IllegalStateException("Нельзя удалить профиль: есть задачи в очереди, ссылающиеся на него");
        }
        repository.delete(e);
        log.info("Deleted docker profile {}", id);
    }

    private void applyResourceDefaults(CreateDockerProfileRequest request, DockerExecutionProfileEntity e) {
        e.setMemoryLimitMb(request.getMemoryLimitMb() != null ? request.getMemoryLimitMb() : 512);
        e.setMemoryReservationMb(request.getMemoryReservationMb() != null ? request.getMemoryReservationMb() : 256);
        e.setCpuLimit(request.getCpuLimit() != null ? request.getCpuLimit() : java.math.BigDecimal.valueOf(0.5));
        e.setCpuShares(request.getCpuShares() != null ? request.getCpuShares() : 512);
        int maxC = request.getMaxConcurrentContainers() != null ? request.getMaxConcurrentContainers() : 1;
        e.setMaxConcurrentContainers(Math.max(1, maxC));
        e.setNetworkMode(request.getNetworkMode() != null ? request.getNetworkMode() : "loadtest_loadtest-network");
        e.setRestartPolicy(request.getRestartPolicy() != null ? request.getRestartPolicy() : "no");
        e.setRestartMaxRetries(request.getRestartMaxRetries());
        e.setLogDriver(request.getLogDriver() != null ? request.getLogDriver() : "json-file");
        e.setLogMaxSize(request.getLogMaxSize() != null ? request.getLogMaxSize() : "10m");
        e.setLogMaxFiles(request.getLogMaxFiles() != null ? request.getLogMaxFiles() : 3);
        e.setEnvironmentVariables(request.getEnvironmentVariables());
        e.setLabels(request.getLabels());
    }

    private DockerProfileDto toDto(DockerExecutionProfileEntity entity) {
        return DockerProfileDto.builder()
                .id(entity.getId())
                .name(entity.getName())
                .dockerHostUri(entity.getDockerHostUri())
                .namedVolumeForChildBinds(entity.getNamedVolumeForChildBinds())
                .memoryLimitMb(entity.getMemoryLimitMb())
                .memoryReservationMb(entity.getMemoryReservationMb())
                .cpuLimit(entity.getCpuLimit())
                .cpuShares(entity.getCpuShares())
                .maxConcurrentContainers(entity.getMaxConcurrentContainers())
                .networkMode(entity.getNetworkMode())
                .restartPolicy(entity.getRestartPolicy())
                .restartMaxRetries(entity.getRestartMaxRetries())
                .logDriver(entity.getLogDriver())
                .logMaxSize(entity.getLogMaxSize())
                .logMaxFiles(entity.getLogMaxFiles())
                .environmentVariables(entity.getEnvironmentVariables())
                .labels(entity.getLabels())
                .enabled(entity.isEnabled())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private void assertNoQueuedTestTasksForDockerMutation() {
        if (queuePauseService.isQueuePaused()) {
            return;
        }
        if (testTaskRepository.count() > 0) {
            throw new IllegalStateException(
                    "Нельзя изменить профили Docker, пока в очереди есть неисполненные задачи (таблица test_task). "
                            + "Включите паузу очереди или дождитесь завершения прогонов.");
        }
    }
}
