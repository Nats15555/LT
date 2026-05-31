package com.loadtest.execution.service;

import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.LogConfig;
import com.loadtest.execution.persistence.DockerExecutionProfileEntity;
import com.loadtest.execution.persistence.LoadTestToolEntity;
import com.loadtest.execution.persistence.LoadTestToolRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommandFromDbServiceTest {

    @Mock
    private LoadTestToolRepository toolRepository;

    private CommandFromDbService service;

    @BeforeEach
    void injectConfig() {
        service = new CommandFromDbService(toolRepository);
        ReflectionTestUtils.setField(service, "workingDir", "");
        ReflectionTestUtils.setField(service, "artifactBasePathFallback", "artifacts");
        ReflectionTestUtils.setField(service, "artifactReportsSubDirFallback", "reports");
        ReflectionTestUtils.setField(service, "artifactMetricsSubDirFallback", "metrics");
        ReflectionTestUtils.setField(service, "defaultNetworkName", "");
        ReflectionTestUtils.setField(service, "namedVolumeForChildBinds", "");
    }

    @Test
    void getToolByName_delegatesToRepository() {
        LoadTestToolEntity e = new LoadTestToolEntity();
        when(toolRepository.findByNameAndEnabledTrue("k6")).thenReturn(Optional.of(e));
        assertThat(service.getToolByName("k6")).containsSame(e);
        verify(toolRepository).findByNameAndEnabledTrue("k6");
    }

    @Test
    void resolveArtifactPaths_usesWorkingDirWhenSet() {
        String wd = Path.of(System.getProperty("java.io.tmpdir"), "lt-art").toString();
        ReflectionTestUtils.setField(service, "workingDir", wd);
        CommandFromDbService.ArtifactPaths p = service.resolveArtifactPaths();
        assertThat(p.reportsPath()).contains("artifacts").contains("reports");
        assertThat(p.metricsPath()).contains("artifacts").contains("metrics");
    }

    @Test
    void buildCommand_blankReturnsEmptyList() {
        assertThat(service.buildCommand(null, Map.of())).isEmpty();
        assertThat(service.buildCommand("   ", Map.of())).isEmpty();
    }

    @Test
    void buildCommand_substitutesAndTokenizes() {
        List<String> cmd = service.buildCommand("run {fileName} --out", Map.of("fileName", "t.js"));
        assertThat(cmd).containsExactly("run", "t.js", "--out");
    }

    @Test
    void buildCommand_substitutesNullPlaceholderAsEmpty() {
        Map<String, String> ph = new HashMap<>();
        ph.put("k", null);
        assertThat(service.buildCommand("a{k}b", ph)).containsExactly("ab");
    }

    @Test
    void deriveArtifactFilePaths_skipsWhenIncomplete() {
        assertThat(service.deriveArtifactFilePathsFromCommand(null, Map.of())).isEmpty();
        assertThat(service.deriveArtifactFilePathsFromCommand("x", null)).isEmpty();
        Map<String, String> ph = new HashMap<>();
        ph.put("reportsHostPath", "/r/");
        ph.put("reportBaseName", "");
        assertThat(service.deriveArtifactFilePathsFromCommand("{reportBaseName}", ph)).isEmpty();
    }

    @Test
    void deriveArtifactFilePaths_collectsReportAndMetrics() {
        Map<String, String> ph = new HashMap<>();
        ph.put("reportsHostPath", "/data/reports/");
        ph.put("metricsHostPath", "/data/metrics/");
        ph.put("reportBaseName", "run1");
        ph.put("metricsBaseName", "run1m");
        assertThat(service.deriveArtifactFilePathsFromCommand("{reportBaseName}.html {metricsBaseName}.json", ph))
                .containsExactly("/data/reports/run1.html", "/data/metrics/run1m.json");
    }

    @Test
    void namedVolumeForChildBinds_emptyWhenUnset() {
        assertThat(service.namedVolumeForChildBinds()).isEmpty();
        ReflectionTestUtils.setField(service, "namedVolumeForChildBinds", "  ");
        assertThat(service.namedVolumeForChildBinds()).isEmpty();
        ReflectionTestUtils.setField(service, "namedVolumeForChildBinds", " vol1 ");
        assertThat(service.namedVolumeForChildBinds()).contains("vol1");
    }

    @Test
    void buildBinds_nullPlaceholdersReturnsEmpty() {
        assertThat(service.buildBinds(null)).isEmpty();
    }

    @Test
    void buildBinds_addsMountsForPaths() {
        Map<String, String> ph = Map.of(
                "testFileHostPath", "/h/test",
                "reportsHostPath", "/h/rep",
                "metricsHostPath", "/h/met"
        );
        assertThat(service.buildBinds(ph)).hasSize(3);
    }

    @Test
    void buildBindsUsingHostPaths_withoutVolume_keepsPaths() {
        Map<String, String> ph = Map.of("testFileHostPath", "/h/test");
        assertThat(service.buildBindsUsingHostPaths(ph, Optional.empty())).hasSize(1);
    }

    @Test
    void mapPathUnderWorkingDir_throwsWhenWorkingDirUnset() {
        ReflectionTestUtils.setField(service, "workingDir", "");
        ReflectionTestUtils.setField(service, "namedVolumeForChildBinds", "v");
        Map<String, String> ph = Map.of("testFileHostPath", "/tmp/x");
        assertThatThrownBy(() -> service.buildBindsUsingHostPaths(ph, Optional.of("/mnt/vol")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("working-dir");
    }

    @Test
    void mapPathUnderWorkingDir_throwsWhenWorkingDirNull() {
        ReflectionTestUtils.setField(service, "workingDir", null);
        ReflectionTestUtils.setField(service, "namedVolumeForChildBinds", "v");
        Map<String, String> ph = Map.of("testFileHostPath", "/tmp/x");
        assertThatThrownBy(() -> service.buildBindsUsingHostPaths(ph, Optional.of("/mnt/vol")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("working-dir");
    }

    @Test
    void hasBindableHostPath_coversNullBlankAndNonBlank() {
        assertThat(CommandFromDbService.hasBindableHostPath(null)).isFalse();
        assertThat(CommandFromDbService.hasBindableHostPath("")).isFalse();
        assertThat(CommandFromDbService.hasBindableHostPath(" \t")).isFalse();
        assertThat(CommandFromDbService.hasBindableHostPath("/mnt/x")).isTrue();
    }

    @Test
    void mapPathUnderWorkingDir_whenWdEqualsDir_returnsVolumeRoot_notRelResolve() throws Exception {
        Method m = CommandFromDbService.class.getDeclaredMethod(
                "mapPathUnderWorkingDirToHostVolumeMountpoint", String.class, String.class);
        m.setAccessible(true);
        Path wd = Files.createTempDirectory("lt-wd-eq-dir").toAbsolutePath().normalize();
        Path hostRoot = Files.createTempDirectory("lt-vol-eq").toAbsolutePath().normalize();
        try {
            ReflectionTestUtils.setField(service, "workingDir", wd.toString());
            String result = (String) m.invoke(service, wd.toString(), hostRoot.toString());
            assertThat(Paths.get(result).normalize()).isEqualTo(hostRoot);
        } finally {
            Files.deleteIfExists(wd);
            Files.deleteIfExists(hostRoot);
        }
    }

    @Test
    void violatesWorkingDirContainment_falseForPlainRelativeChild() {
        assertThat(CommandFromDbService.violatesWorkingDirContainment(Paths.get("child", "dir"))).isFalse();
    }

    @Test
    void violatesWorkingDirContainment_trueWhenPathStartsWithParent() {
        assertThat(CommandFromDbService.violatesWorkingDirContainment(Paths.get("..", "outside"))).isTrue();
    }

    @Test
    void violatesWorkingDirContainment_trueForFilesystemRoot() {
        Path root = Paths.get(System.getProperty("user.dir")).toAbsolutePath().getRoot();
        assertThat(CommandFromDbService.violatesWorkingDirContainment(root)).isTrue();
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void violatesWorkingDirContainment_trueForOtherDriveLetterWithoutDrivePresent() {
        assertThat(CommandFromDbService.violatesWorkingDirContainment(Path.of("Q:\\no\\such\\path"))).isTrue();
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void buildBindsUsingHostPaths_crossDrive_throwsMustBeUnder() {
        ReflectionTestUtils.setField(service, "workingDir", "C:\\lt-work");
        Map<String, String> ph = Map.of("testFileHostPath", "Q:\\other\\path");
        assertThatThrownBy(() -> service.buildBindsUsingHostPaths(ph, Optional.of("/mnt/vol")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be under");
    }

    @Test
    void buildBindsUsingHostPaths_mapsUnderWorkingDir() {
        Path wd = Path.of(System.getProperty("java.io.tmpdir"), "lt-wd").toAbsolutePath().normalize();
        Path sub = wd.resolve("child").normalize();
        ReflectionTestUtils.setField(service, "workingDir", wd.toString());
        Map<String, String> ph = Map.of("testFileHostPath", sub.toString());
        List<?> binds = service.buildBindsUsingHostPaths(ph, Optional.of("/docker/volume"));
        assertThat(binds).hasSize(1);
    }

    @Test
    void buildBindsUsingHostPaths_throwsWhenPathOutsideWorkingDir() {
        Path wd = Path.of(System.getProperty("java.io.tmpdir"), "lt-wd2").toAbsolutePath().normalize();
        ReflectionTestUtils.setField(service, "workingDir", wd.toString());
        Map<String, String> ph = Map.of("testFileHostPath", "/etc/passwd");
        assertThatThrownBy(() -> service.buildBindsUsingHostPaths(ph, Optional.of("/docker/vol")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be under");
    }

    @Test
    void applyDockerProfile_appliesLimitsAndSkipsInvalidFragments() {
        DockerExecutionProfileEntity cfg = DockerExecutionProfileEntity.builder()
                .memoryLimitMb(128)
                .memoryReservationMb(64)
                .cpuLimit(BigDecimal.valueOf(0.5))
                .cpuShares(512)
                .networkMode("bridge")
                .restartPolicy("noSuchPolicy")
                .logDriver("___not_a_real_driver___")
                .logMaxSize("10m")
                .logMaxFiles(3)
                .build();
        HostConfig hc = service.applyDockerProfile(null, cfg);
        assertThat(hc.getMemory()).isEqualTo(128L * 1024 * 1024);
        assertThat(hc.getMemoryReservation()).isEqualTo(64L * 1024 * 1024);
        assertThat(hc.getNetworkMode()).isEqualTo("bridge");
    }

    @Test
    void applyDockerProfile_usesDefaultNetworkWhenProfileBlank() {
        ReflectionTestUtils.setField(service, "defaultNetworkName", "loadtest-network");
        DockerExecutionProfileEntity cfg = DockerExecutionProfileEntity.builder().build();
        HostConfig hc = service.applyDockerProfile(HostConfig.newHostConfig(), cfg);
        assertThat(hc.getNetworkMode()).isEqualTo("loadtest-network");
    }

    @Test
    void resolveNamedVolumeForChildBinds_prefersProfile() {
        DockerExecutionProfileEntity p = DockerExecutionProfileEntity.builder()
                .namedVolumeForChildBinds(" from-profile ")
                .build();
        assertThat(service.resolveNamedVolumeForChildBinds(p)).contains("from-profile");
    }

    @Test
    void resolveNamedVolumeForChildBinds_fallsBackToYaml() {
        ReflectionTestUtils.setField(service, "namedVolumeForChildBinds", "from-yml");
        assertThat(service.resolveNamedVolumeForChildBinds(null)).contains("from-yml");
        assertThat(service.resolveNamedVolumeForChildBinds(DockerExecutionProfileEntity.builder().build()))
                .contains("from-yml");
    }

    @Test
    void resolveNamedVolumeForChildBinds_blankProfileUsesYaml() {
        ReflectionTestUtils.setField(service, "namedVolumeForChildBinds", "vol-yml");
        DockerExecutionProfileEntity p = DockerExecutionProfileEntity.builder()
                .namedVolumeForChildBinds("   ")
                .build();
        assertThat(service.resolveNamedVolumeForChildBinds(p)).contains("vol-yml");
    }

    @Test
    void resolveArtifactPaths_withoutWorkingDirUsesCurrentDirectory() {
        ReflectionTestUtils.setField(service, "workingDir", "");
        CommandFromDbService.ArtifactPaths p = service.resolveArtifactPaths();
        assertThat(p.reportsPath()).isNotBlank();
    }

    @Test
    void buildCommand_nullPlaceholdersUsesEmptyMap() {
        assertThat(service.buildCommand("one two", null)).containsExactly("one", "two");
    }

    @Test
    void buildCommand_skipsEmptyTokens() {
        assertThat(service.buildCommand("  a   b  ", Map.of())).containsExactly("a", "b");
    }

    @Test
    void buildCommand_noSubstitutionWhenPlaceholdersEmpty() {
        assertThat(service.buildCommand("{x}", Map.of())).containsExactly("{x}");
    }

    @Test
    void deriveArtifactFilePaths_metricsOnly() {
        Map<String, String> ph = new HashMap<>();
        ph.put("metricsHostPath", "/m");
        ph.put("reportsHostPath", "");
        ph.put("reportBaseName", "r");
        ph.put("metricsBaseName", "m");
        assertThat(service.deriveArtifactFilePathsFromCommand("{metricsBaseName}.json", ph))
                .containsExactly("/m/m.json");
    }

    @Test
    void namedVolumeForChildBinds_nullField() {
        ReflectionTestUtils.setField(service, "namedVolumeForChildBinds", null);
        assertThat(service.namedVolumeForChildBinds()).isEmpty();
    }

    @Test
    void buildBindsUsingHostPaths_partialMounts() {
        assertThat(service.buildBindsUsingHostPaths(Map.of("testFileHostPath", "/t"), Optional.empty())).hasSize(1);
    }

    @Test
    void buildBindsUsingHostPaths_mapsWhenDirEqualsWorkingDir() {
        Path wd = Path.of(System.getProperty("java.io.tmpdir"), "lt-wd3").toAbsolutePath().normalize();
        ReflectionTestUtils.setField(service, "workingDir", wd.toString());
        Map<String, String> ph = Map.of("testFileHostPath", wd.toString());
        assertThat(service.buildBindsUsingHostPaths(ph, Optional.of("/vol"))).hasSize(1);
    }

    @Test
    void applyDockerProfile_minimalConfigNoNetwork() {
        ReflectionTestUtils.setField(service, "defaultNetworkName", "");
        HostConfig hc = service.applyDockerProfile(null, DockerExecutionProfileEntity.builder().build());
        assertThat(hc.getMemory()).isNull();
    }

    @Test
    void applyDockerProfile_validRestartAndLogOptions() {
        DockerExecutionProfileEntity cfg = DockerExecutionProfileEntity.builder()
                .restartPolicy("no")
                .logDriver("json-file")
                .logMaxSize("10m")
                .logMaxFiles(2)
                .build();
        HostConfig hc = service.applyDockerProfile(HostConfig.newHostConfig(), cfg);
        assertThat(hc.getRestartPolicy()).isNotNull();
        assertThat(hc.getLogConfig()).isNotNull();
    }

    @Test
    void applyDockerProfile_cpuLimitApplied() {
        DockerExecutionProfileEntity cfg = DockerExecutionProfileEntity.builder()
                .cpuLimit(BigDecimal.valueOf(1.5))
                .cpuShares(256)
                .memoryLimitMb(0)
                .memoryReservationMb(0)
                .build();
        HostConfig hc = service.applyDockerProfile(null, cfg);
        assertThat(hc.getCpuQuota()).isNotNull();
        assertThat(hc.getCpuShares()).isEqualTo(256);
    }

    @Test
    void substitute_nullPlaceholders_returnsOriginal() throws Exception {
        Method m = CommandFromDbService.class.getDeclaredMethod("substitute", String.class, Map.class);
        m.setAccessible(true);
        assertThat(m.invoke(service, "a{b}", null)).isEqualTo("a{b}");
    }

    @Test
    void derive_blankCommandReturnsEmpty() {
        assertThat(service.deriveArtifactFilePathsFromCommand(" ", Map.of("reportsHostPath", "/r"))).isEmpty();
    }

    @Test
    void derive_skipsReportWhenHostPathMissing() {
        Map<String, String> ph = new HashMap<>();
        ph.put("reportsHostPath", "");
        ph.put("reportBaseName", "x");
        assertThat(service.deriveArtifactFilePathsFromCommand("{reportBaseName}.html", ph)).isEmpty();
    }

    @Test
    void derive_skipsMetricsWhenHostPathMissing() {
        Map<String, String> ph = new HashMap<>();
        ph.put("metricsHostPath", "");
        ph.put("reportBaseName", "r");
        ph.put("metricsBaseName", "m");
        assertThat(service.deriveArtifactFilePathsFromCommand("{metricsBaseName}.json", ph)).isEmpty();
    }

    @Test
    void derive_skipsReportWhenDerivedFileNameEmpty() {
        Map<String, String> ph = new HashMap<>();
        ph.put("reportsHostPath", "/reports");
        ph.put("reportBaseName", "");
        assertThat(service.deriveArtifactFilePathsFromCommand("{reportBaseName}", ph)).isEmpty();
    }

    @Test
    void derive_skipsMetricsWhenDerivedFileNameEmpty() {
        Map<String, String> ph = new HashMap<>();
        ph.put("metricsHostPath", "/metrics");
        ph.put("reportBaseName", "r");
        ph.put("metricsBaseName", "");
        assertThat(service.deriveArtifactFilePathsFromCommand("{metricsBaseName}", ph)).isEmpty();
    }

    @Test
    void buildBindsUsingHostPaths_skipsBlankPaths() {
        Map<String, String> ph = new HashMap<>();
        ph.put("testFileHostPath", "");
        ph.put("reportsHostPath", "/rep");
        ph.put("metricsHostPath", "");
        assertThat(service.buildBindsUsingHostPaths(ph, Optional.empty())).hasSize(1);
    }

    @Test
    void buildBindsUsingHostPaths_skipsAllMountsWhenPathsBlank() {
        Map<String, String> ph = new HashMap<>();
        ph.put("testFileHostPath", "");
        ph.put("reportsHostPath", "");
        ph.put("metricsHostPath", "");
        assertThat(service.buildBindsUsingHostPaths(ph, Optional.empty())).isEmpty();
    }

    @Test
    void applyDockerProfile_logDriverWithoutSizeOrFiles() {
        DockerExecutionProfileEntity cfg = DockerExecutionProfileEntity.builder()
                .logDriver("json-file")
                .build();
        HostConfig hc = service.applyDockerProfile(null, cfg);
        assertThat(hc.getLogConfig()).isNotNull();
    }

    @Test
    void applyDockerProfile_memoryReservationOnly() {
        DockerExecutionProfileEntity cfg = DockerExecutionProfileEntity.builder()
                .memoryReservationMb(32)
                .build();
        HostConfig hc = service.applyDockerProfile(null, cfg);
        assertThat(hc.getMemoryReservation()).isEqualTo(32L * 1024 * 1024);
    }

    @Test
    void resolveArtifactPaths_workingDirNullFallsBackToDot() {
        ReflectionTestUtils.setField(service, "workingDir", null);
        assertThat(service.resolveArtifactPaths().reportsPath()).isNotBlank();
    }

    @Test
    void applyDockerProfile_cpuLimit_catchWhenWithCpuQuotaThrows() {
        HostConfig base = spy(HostConfig.newHostConfig());
        doThrow(new RuntimeException("cpu-quota-mock")).when(base).withCpuQuota(anyLong());
        DockerExecutionProfileEntity cfg = DockerExecutionProfileEntity.builder()
                .cpuLimit(BigDecimal.valueOf(0.5))
                .build();
        HostConfig hc = service.applyDockerProfile(base, cfg);
        assertThat(hc).isSameAs(base);
        verify(base).withCpuQuota(anyLong());
    }

    @Test
    void applyDockerProfile_logConfig_catchWhenWithLogConfigThrows() {
        HostConfig base = spy(HostConfig.newHostConfig());
        doThrow(new RuntimeException("log-config-mock")).when(base).withLogConfig(any(LogConfig.class));
        DockerExecutionProfileEntity cfg = DockerExecutionProfileEntity.builder()
                .logDriver("json-file")
                .logMaxSize("5m")
                .logMaxFiles(1)
                .build();
        HostConfig hc = service.applyDockerProfile(base, cfg);
        assertThat(hc).isSameAs(base);
        verify(base).withLogConfig(any(LogConfig.class));
    }

    @Test
    void applyDockerProfile_skipsOptionalBlocksWhenUnset() {
        ReflectionTestUtils.setField(service, "defaultNetworkName", "");
        DockerExecutionProfileEntity cfg = DockerExecutionProfileEntity.builder()
                .cpuLimit(BigDecimal.ZERO)
                .cpuShares(0)
                .networkMode(null)
                .restartPolicy("   ")
                .logDriver(null)
                .memoryLimitMb(null)
                .memoryReservationMb(null)
                .build();
        HostConfig hc = service.applyDockerProfile(HostConfig.newHostConfig(), cfg);
        assertThat(hc.getCpuQuota()).isNull();
        assertThat(hc.getCpuShares()).isNull();
        assertThat(hc.getNetworkMode()).isNull();
        assertThat(hc.getRestartPolicy()).isNull();
        assertThat(hc.getLogConfig().getType()).isNull();
        assertThat(hc.getMemory()).isNull();
        assertThat(hc.getMemoryReservation()).isNull();
    }

    @Test
    void applyDockerProfile_skipsLogDriverWhenBlank() {
        DockerExecutionProfileEntity cfg = DockerExecutionProfileEntity.builder()
                .logDriver("  ")
                .memoryLimitMb(64)
                .build();
        HostConfig hc = service.applyDockerProfile(null, cfg);
        assertThat(hc.getLogConfig().getType()).isNull();
        assertThat(hc.getMemory()).isNotNull();
    }

    @Test
    void applyDockerProfile_skipsRestartPolicyWhenBlank() {
        DockerExecutionProfileEntity cfg = DockerExecutionProfileEntity.builder()
                .restartPolicy("")
                .memoryLimitMb(64)
                .build();
        HostConfig hc = service.applyDockerProfile(null, cfg);
        assertThat(hc.getRestartPolicy()).isNull();
    }

    @Test
    void applyDockerProfile_networkFallsBackWhenProfileBlankAndDefaultUnset() {
        ReflectionTestUtils.setField(service, "defaultNetworkName", "");
        DockerExecutionProfileEntity cfg = DockerExecutionProfileEntity.builder()
                .networkMode(null)
                .memoryLimitMb(32)
                .build();
        HostConfig hc = service.applyDockerProfile(null, cfg);
        assertThat(hc.getNetworkMode()).isNull();
    }

    @Test
    void applyDockerProfile_networkFallsBackWhenProfileWhitespaceAndDefaultNull() {
        ReflectionTestUtils.setField(service, "defaultNetworkName", null);
        DockerExecutionProfileEntity cfg = DockerExecutionProfileEntity.builder()
                .networkMode(" \t ")
                .memoryLimitMb(32)
                .build();
        HostConfig hc = service.applyDockerProfile(null, cfg);
        assertThat(hc.getNetworkMode()).isNull();
    }
}
