package com.loadtest.app.service;

import com.loadtest.app.dto.CreateDockerProfileRequest;
import com.loadtest.app.dto.DockerProfileDto;
import com.loadtest.app.dto.UpdateDockerProfileRequest;
import com.loadtest.app.persistence.DockerExecutionProfileEntity;
import com.loadtest.app.persistence.DockerExecutionProfileRepository;
import com.loadtest.app.persistence.TestTaskRepository;
import com.loadtest.app.util.ApiMessages;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

@Slf4j
@Service
@RequiredArgsConstructor
public class DockerExecutionProfileService {

    public static final String DEFAULT_PROFILE_NAME = "Default";

    private final DockerExecutionProfileRepository repository;
    private final TestTaskRepository testTaskRepository;
    private final QueuePauseService queuePauseService;

    public UUID resolveProfileIdForUpload(String dockerExecutionProfileIdParam) {
        if (dockerExecutionProfileIdParam == null || dockerExecutionProfileIdParam.isBlank()) {
            throw new IllegalArgumentException(ApiMessages.Upload.DOCKER_EXECUTION_PROFILE_ID_REQUIRED);
        }
        UUID id;
        try {
            id = UUID.fromString(dockerExecutionProfileIdParam.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    ApiMessages.DockerProfile.invalidDockerExecutionProfileId(dockerExecutionProfileIdParam));
        }
        DockerExecutionProfileEntity p = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(
                        ApiMessages.DockerProfile.dockerExecutionProfileIdNotFound(id)));
        if (!p.isEnabled()) {
            throw new IllegalArgumentException(ApiMessages.DockerProfile.dockerProfileDisabled(id));
        }
        return id;
    }

    @Transactional(readOnly = true)
    public List<DockerProfileDto> listProfiles() {
        return repository.findAllByOrderByNameAsc().stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public List<DockerProfileDto> listEnabledProfiles() {
        return repository.findAllByEnabledTrueOrderByNameAsc().stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public DockerProfileDto getById(UUID id) {
        return repository.findById(id).map(this::toDto)
                .orElseThrow(() -> new IllegalArgumentException(ApiMessages.DockerProfile.profileNotFound(id)));
    }

    @Transactional
    public DockerProfileDto create(CreateDockerProfileRequest request) {
        assertNoQueuedTestTasksForDockerMutation();
        String name = request.name().trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException(ApiMessages.DockerProfile.PROFILE_NAME_REQUIRED);
        }
        if (repository.existsByName(name)) {
            throw new IllegalStateException(ApiMessages.DockerProfile.profileNameAlreadyExists(name));
        }
        OffsetDateTime now = OffsetDateTime.now();
        DockerExecutionProfileEntity e = DockerExecutionProfileEntity.builder()
                .id(UUID.randomUUID())
                .name(name)
                .dockerHostUri(trimToNull(request.dockerHostUri()))
                .namedVolumeForChildBinds(trimToNull(request.namedVolumeForChildBinds()))
                .enabled(request.enabled() == null || Boolean.TRUE.equals(request.enabled()))
                .createdAt(now)
                .updatedAt(now)
                .build();
        applyResourceDefaults(request, e);
        return toDto(repository.save(e));
    }

    @Transactional
    public DockerProfileDto update(UUID id, UpdateDockerProfileRequest request) {
        assertNoQueuedTestTasksForDockerMutation();
        DockerExecutionProfileEntity entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(ApiMessages.DockerProfile.profileNotFound(id)));
        applyProfileNameUpdate(entity, request);
        applyProfileFieldUpdates(entity, request);
        entity.setUpdatedAt(OffsetDateTime.now());
        return toDto(repository.save(entity));
    }

    @Transactional
    public void delete(UUID id) {
        assertNoQueuedTestTasksForDockerMutation();
        DockerExecutionProfileEntity e = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(ApiMessages.DockerProfile.profileNotFound(id)));
        if (DEFAULT_PROFILE_NAME.equals(e.getName())) {
            throw new IllegalStateException(ApiMessages.DockerProfile.cannotDeleteSystemProfile(e.getName()));
        }
        long queued = testTaskRepository.countByDockerExecutionProfileId(id);
        if (queued > 0) {
            throw new IllegalStateException(ApiMessages.DockerProfile.CANNOT_DELETE_WITH_QUEUED_TASKS);
        }
        repository.delete(e);
        log.info("Deleted docker profile {}", id);
    }

    private void applyResourceDefaults(CreateDockerProfileRequest request, DockerExecutionProfileEntity e) {
        e.setMemoryLimitMb(request.memoryLimitMb() != null ? request.memoryLimitMb() : 512);
        e.setMemoryReservationMb(request.memoryReservationMb() != null ? request.memoryReservationMb() : 256);
        e.setCpuLimit(request.cpuLimit() != null ? request.cpuLimit() : BigDecimal.valueOf(0.5));
        e.setCpuShares(request.cpuShares() != null ? request.cpuShares() : 512);
        int maxC = request.maxConcurrentContainers() != null ? request.maxConcurrentContainers() : 1;
        e.setMaxConcurrentContainers(Math.max(1, maxC));
        e.setNetworkMode(request.networkMode() != null ? request.networkMode() : ApiMessages.DockerProfile.DEFAULT_NETWORK_MODE);
        e.setRestartPolicy(request.restartPolicy() != null ? request.restartPolicy() : ApiMessages.DockerProfile.DEFAULT_RESTART_POLICY);
        e.setRestartMaxRetries(request.restartMaxRetries());
        e.setLogDriver(request.logDriver() != null ? request.logDriver() : ApiMessages.DockerProfile.DEFAULT_LOG_DRIVER);
        e.setLogMaxSize(request.logMaxSize() != null ? request.logMaxSize() : ApiMessages.DockerProfile.DEFAULT_LOG_MAX_SIZE);
        e.setLogMaxFiles(request.logMaxFiles() != null ? request.logMaxFiles() : 3);
        e.setEnvironmentVariables(request.environmentVariables());
        e.setLabels(request.labels());
    }

    private void applyProfileNameUpdate(DockerExecutionProfileEntity entity, UpdateDockerProfileRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            return;
        }
        String newName = request.name().trim();
        if (!newName.equals(entity.getName()) && repository.existsByName(newName)) {
            throw new IllegalStateException(ApiMessages.DockerProfile.profileNameAlreadyExists(newName));
        }
        entity.setName(newName);
    }

    private void applyProfileFieldUpdates(DockerExecutionProfileEntity entity, UpdateDockerProfileRequest request) {
        setTrimmedIfPresent(request.dockerHostUri(), entity::setDockerHostUri);
        setTrimmedIfPresent(request.namedVolumeForChildBinds(), entity::setNamedVolumeForChildBinds);
        setIfPresent(request.memoryLimitMb(), entity::setMemoryLimitMb);
        setIfPresent(request.memoryReservationMb(), entity::setMemoryReservationMb);
        setIfPresent(request.cpuLimit(), entity::setCpuLimit);
        setIfPresent(request.cpuShares(), entity::setCpuShares);
        if (request.maxConcurrentContainers() != null) {
            entity.setMaxConcurrentContainers(Math.max(1, request.maxConcurrentContainers()));
        }
        setIfPresent(request.networkMode(), entity::setNetworkMode);
        setIfPresent(request.restartPolicy(), entity::setRestartPolicy);
        setIfPresent(request.restartMaxRetries(), entity::setRestartMaxRetries);
        setIfPresent(request.logDriver(), entity::setLogDriver);
        setIfPresent(request.logMaxSize(), entity::setLogMaxSize);
        setIfPresent(request.logMaxFiles(), entity::setLogMaxFiles);
        setIfPresent(request.environmentVariables(), entity::setEnvironmentVariables);
        setIfPresent(request.labels(), entity::setLabels);
        setIfPresent(request.enabled(), entity::setEnabled);
    }

    private static <T> void setIfPresent(T value, Consumer<T> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }

    private void setTrimmedIfPresent(String value, Consumer<String> setter) {
        if (value != null) {
            setter.accept(trimToNull(value));
        }
    }

    private DockerProfileDto toDto(DockerExecutionProfileEntity entity) {
        return new DockerProfileDto(
                entity.getId(),
                entity.getName(),
                entity.getDockerHostUri(),
                entity.getNamedVolumeForChildBinds(),
                entity.getMemoryLimitMb(),
                entity.getMemoryReservationMb(),
                entity.getCpuLimit(),
                entity.getCpuShares(),
                entity.getMaxConcurrentContainers(),
                entity.getNetworkMode(),
                entity.getRestartPolicy(),
                entity.getRestartMaxRetries(),
                entity.getLogDriver(),
                entity.getLogMaxSize(),
                entity.getLogMaxFiles(),
                entity.getEnvironmentVariables(),
                entity.getLabels(),
                entity.isEnabled(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
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
            throw new IllegalStateException(ApiMessages.DockerProfile.CANNOT_MUTATE_WHILE_QUEUE_HAS_TASKS);
        }
    }
}
