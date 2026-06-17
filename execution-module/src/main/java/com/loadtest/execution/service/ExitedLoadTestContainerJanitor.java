package com.loadtest.execution.service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import com.loadtest.execution.ContainerExecutionService;
import com.loadtest.execution.util.ExitedContainerRetentionEvaluator;
import com.loadtest.execution.util.LoadTestContainerLabels;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExitedLoadTestContainerJanitor {

    private final ContainerExecutionService containerExecutionService;

    @Value("${loadtest.execution.exited-container-retention-hours:48}")
    private long retentionHours;

    @Scheduled(fixedDelayString = "${loadtest.execution.exited-container-cleanup-interval-ms:3600000}")
    public void cleanupExpiredExitedContainers() {
        if (retentionHours <= 0) {
            log.debug("Exited container cleanup disabled (retention-hours={})", retentionHours);
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        containerExecutionService.forEachDistinctDockerClient(client -> cleanupOnClient(client, now));
    }

    private void cleanupOnClient(DockerClient docker, OffsetDateTime now) {
        List<Container> containers;
        try {
            containers = docker.listContainersCmd()
                    .withShowAll(true)
                    .withLabelFilter(Map.of(LoadTestContainerLabels.MANAGED, LoadTestContainerLabels.MANAGED_VALUE))
                    .exec();
        } catch (RuntimeException e) {
            log.warn("Failed to list loadtest containers for cleanup: {}", e.getMessage());
            return;
        }

        int removed = 0;
        int skipped = 0;
        for (Container container : containers) {
            if (!"exited".equalsIgnoreCase(container.getState())) {
                continue;
            }
            if (tryRemoveIfRetentionExpired(docker, container.getId(), now)) {
                removed++;
            } else {
                skipped++;
            }
        }
        if (removed > 0 || skipped > 0) {
            log.info("Exited loadtest container cleanup: removed={}, keptYoungerThan{}h={}",
                    removed, retentionHours, skipped);
        }
    }

    private boolean tryRemoveIfRetentionExpired(DockerClient docker, String containerId, OffsetDateTime now) {
        try {
            InspectContainerResponse inspect = docker.inspectContainerCmd(containerId).exec();
            if (inspect.getState() == null || !"exited".equalsIgnoreCase(inspect.getState().getStatus())) {
                return false;
            }
            var finishedAt = ExitedContainerRetentionEvaluator.parseDockerFinishedAt(
                    inspect.getState().getFinishedAt());
            if (finishedAt.isEmpty()) {
                log.debug("Skip exited container {} — finishedAt is missing", containerId);
                return false;
            }
            if (!ExitedContainerRetentionEvaluator.shouldRemove(finishedAt.get(), now, retentionHours)) {
                return false;
            }
            docker.removeContainerCmd(containerId).exec();
            String name = inspect.getName() != null ? inspect.getName() : containerId;
            log.info("Removed exited loadtest container {} (finishedAt={}, retention={}h)",
                    name, finishedAt.get(), retentionHours);
            return true;
        } catch (NotFoundException e) {
            log.debug("Container {} already removed", containerId);
            return false;
        } catch (RuntimeException e) {
            log.warn("Failed to remove exited container {}: {}", containerId, e.getMessage());
            return false;
        }
    }
}
