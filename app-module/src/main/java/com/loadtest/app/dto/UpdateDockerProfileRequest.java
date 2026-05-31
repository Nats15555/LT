package com.loadtest.app.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

public record UpdateDockerProfileRequest(
        String name,
        @JsonProperty("dockerHostUri") String dockerHostUri,
        @JsonProperty("namedVolumeForChildBinds") String namedVolumeForChildBinds,
        @JsonProperty("memoryLimitMb") Integer memoryLimitMb,
        @JsonProperty("memoryReservationMb") Integer memoryReservationMb,
        @JsonProperty("cpuLimit") BigDecimal cpuLimit,
        @JsonProperty("cpuShares") Integer cpuShares,
        @JsonProperty("maxConcurrentContainers") Integer maxConcurrentContainers,
        @JsonProperty("networkMode") String networkMode,
        @JsonProperty("restartPolicy") String restartPolicy,
        @JsonProperty("restartMaxRetries") Integer restartMaxRetries,
        @JsonProperty("logDriver") String logDriver,
        @JsonProperty("logMaxSize") String logMaxSize,
        @JsonProperty("logMaxFiles") Integer logMaxFiles,
        @JsonProperty("environmentVariables") String environmentVariables,
        @JsonProperty("labels") String labels,
        Boolean enabled) {
}
