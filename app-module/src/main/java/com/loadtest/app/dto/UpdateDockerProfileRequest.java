package com.loadtest.app.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateDockerProfileRequest {

    private String name;

    @JsonProperty("dockerHostUri")
    private String dockerHostUri;

    @JsonProperty("namedVolumeForChildBinds")
    private String namedVolumeForChildBinds;

    @JsonProperty("memoryLimitMb")
    private Integer memoryLimitMb;

    @JsonProperty("memoryReservationMb")
    private Integer memoryReservationMb;

    @JsonProperty("cpuLimit")
    private BigDecimal cpuLimit;

    @JsonProperty("cpuShares")
    private Integer cpuShares;

    @JsonProperty("maxConcurrentContainers")
    private Integer maxConcurrentContainers;

    @JsonProperty("networkMode")
    private String networkMode;

    @JsonProperty("restartPolicy")
    private String restartPolicy;

    @JsonProperty("restartMaxRetries")
    private Integer restartMaxRetries;

    @JsonProperty("logDriver")
    private String logDriver;

    @JsonProperty("logMaxSize")
    private String logMaxSize;

    @JsonProperty("logMaxFiles")
    private Integer logMaxFiles;

    @JsonProperty("environmentVariables")
    private String environmentVariables;

    @JsonProperty("labels")
    private String labels;

    private Boolean enabled;
}
