package com.loadtest.execution.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionPersistenceLombokBranchExhaustiveTest {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ID2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID PROFILE_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final OffsetDateTime T1 = OffsetDateTime.parse("2024-01-01T00:00:00Z");
    private static final OffsetDateTime T2 = OffsetDateTime.parse("2024-01-02T00:00:00Z");

    private static final Class<?>[] ENTITY_TYPES = new Class<?>[] {
            DockerExecutionProfileEntity.class,
            LoadTestToolEntity.class,
            TestArtifactEntity.class,
            TestMetricsEntity.class,
            TestSummaryEntity.class,
            TestTaskEntity.class,
            TestTaskHistoryEntity.class
    };

    @Test
    void entities_canEqual_trueAndFalseBranches() throws Exception {
        for (Class<?> type : ENTITY_TYPES) {
            assertCanEqualBranches(type);
        }
    }

    @Test
    void entities_nullField_equalsBranches() throws Exception {
        for (Class<?> type : ENTITY_TYPES) {
            assertNullFieldBranches(type);
        }
    }

    @Test
    void entities_equalsSelf_andHashCodeNullBranches() throws Exception {
        for (Class<?> type : ENTITY_TYPES) {
            assertEqualsSelfAndHashCodeNullBranches(type);
        }
    }

    @Test
    void entities_equals_whenBothSidesHaveNullReferenceFields() throws Exception {
        for (Class<?> type : ENTITY_TYPES) {
            assertEqualsWithBothNullFieldValues(type);
        }
    }

    @Test
    void testArtifactEntity_equals_hashCode_explicitBranches() {
        TestArtifactEntity base = sampleArtifact();

        assertThat(base).isEqualTo(base);
        assertThat(base).isNotEqualTo(null);
        assertThat(base).isNotEqualTo("x");
        assertThat(base).isNotEqualTo(sampleArtifactBuilder().fileName("other").build());
        assertThat(base).isNotEqualTo(sampleArtifactBuilder().fileContent(new byte[] {9}).build());
        assertThat(base).isNotEqualTo(sampleArtifactBuilder().contentEncoding("identity").build());
        assertThat(base).isNotEqualTo(sampleArtifactBuilder().originalSizeBytes(99L).build());
        assertThat(base).isNotEqualTo(sampleArtifactBuilder().compressedSizeBytes(99L).build());
        assertThat(base).isNotEqualTo(sampleArtifactBuilder().createdAt(T2).build());

        TestArtifactEntity na = sampleArtifactBuilder().originalSizeBytes(null).compressedSizeBytes(null).build();
        TestArtifactEntity nb = sampleArtifactBuilder().originalSizeBytes(null).compressedSizeBytes(null).build();
        assertThat(na).isEqualTo(nb);
        assertThat(na.hashCode()).isEqualTo(nb.hashCode());
        na.hashCode();

        TestArtifactEntityChild child = new TestArtifactEntityChild();
        BeanUtils.copyProperties(base, child);
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(base, "canEqual", child)).isTrue();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(base, "canEqual", "x")).isFalse();
        assertThat(base).isEqualTo(child);
        assertThat(child).isEqualTo(base);
    }

    @Test
    void dockerExecutionProfile_subclassEquals_andCanEqualFalse() {
        DockerExecutionProfileEntity base = sampleDocker();
        DockerExecutionProfileEntityChild child = new DockerExecutionProfileEntityChild();
        BeanUtils.copyProperties(base, child);
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(base, "canEqual", child)).isTrue();
        assertThat(base).isEqualTo(child);

        DockerExecutionProfileEntityCanEqualFalse other = new DockerExecutionProfileEntityCanEqualFalse();
        BeanUtils.copyProperties(base, other);
        assertThat(base).isNotEqualTo(other);
    }

    @Test
    void loadTestTool_subclassEquals_andCanEqualFalse() {
        LoadTestToolEntity base = sampleLoadTestTool();
        LoadTestToolEntityChild child = new LoadTestToolEntityChild();
        BeanUtils.copyProperties(base, child);
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(base, "canEqual", child)).isTrue();
        assertThat(base).isEqualTo(child);

        LoadTestToolEntityCanEqualFalse other = new LoadTestToolEntityCanEqualFalse();
        BeanUtils.copyProperties(base, other);
        assertThat(base).isNotEqualTo(other);
    }

    @Test
    void testArtifact_subclassEquals_andCanEqualFalse() {
        TestArtifactEntity base = sampleArtifact();
        TestArtifactEntityChild child = new TestArtifactEntityChild();
        BeanUtils.copyProperties(base, child);
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(base, "canEqual", child)).isTrue();
        assertThat(base).isEqualTo(child);

        TestArtifactEntityCanEqualFalse other = new TestArtifactEntityCanEqualFalse();
        BeanUtils.copyProperties(base, other);
        assertThat(base).isNotEqualTo(other);
    }

    @Test
    void testMetrics_subclassEquals_andCanEqualFalse() {
        TestMetricsEntity base = sampleMetrics();
        TestMetricsEntityChild child = new TestMetricsEntityChild();
        BeanUtils.copyProperties(base, child);
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(base, "canEqual", child)).isTrue();
        assertThat(base).isEqualTo(child);

        TestMetricsEntityCanEqualFalse other = new TestMetricsEntityCanEqualFalse();
        BeanUtils.copyProperties(base, other);
        assertThat(base).isNotEqualTo(other);
    }

    @Test
    void testSummary_subclassEquals_andCanEqualFalse() {
        TestSummaryEntity base = sampleSummary();
        TestSummaryEntityChild child = new TestSummaryEntityChild();
        BeanUtils.copyProperties(base, child);
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(base, "canEqual", child)).isTrue();
        assertThat(base).isEqualTo(child);

        TestSummaryEntityCanEqualFalse other = new TestSummaryEntityCanEqualFalse();
        BeanUtils.copyProperties(base, other);
        assertThat(base).isNotEqualTo(other);
    }

    @Test
    void testTask_subclassEquals_andCanEqualFalse() {
        TestTaskEntity base = sampleTask();
        TestTaskEntityChild child = new TestTaskEntityChild();
        BeanUtils.copyProperties(base, child);
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(base, "canEqual", child)).isTrue();
        assertThat(base).isEqualTo(child);

        TestTaskEntityCanEqualFalse other = new TestTaskEntityCanEqualFalse();
        BeanUtils.copyProperties(base, other);
        assertThat(base).isNotEqualTo(other);
    }

    @Test
    void testTaskHistory_subclassEquals_andCanEqualFalse() {
        TestTaskHistoryEntity base = sampleHistory();
        TestTaskHistoryEntityChild child = new TestTaskHistoryEntityChild();
        BeanUtils.copyProperties(base, child);
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(base, "canEqual", child)).isTrue();
        assertThat(base).isEqualTo(child);

        TestTaskHistoryEntityCanEqualFalse other = new TestTaskHistoryEntityCanEqualFalse();
        BeanUtils.copyProperties(base, other);
        assertThat(base).isNotEqualTo(other);
    }

    @Test
    void dockerExecutionProfileEntity_equals_hashCode_explicitBranches() {
        DockerExecutionProfileEntity base = sampleDocker();
        assertThat(base).isEqualTo(base);
        assertThat(base).isNotEqualTo(null);
        assertThat(base).isNotEqualTo("x");
        assertThat(base).isNotEqualTo(dockerLike(base).id(ID2).build());
        assertThat(base).isNotEqualTo(dockerLike(base).name("x").build());
        assertThat(base).isNotEqualTo(dockerLike(base).dockerHostUri("x").build());
        assertThat(base).isNotEqualTo(dockerLike(base).namedVolumeForChildBinds("x").build());
        assertThat(base).isNotEqualTo(dockerLike(base).memoryLimitMb(9).build());
        assertThat(base).isNotEqualTo(dockerLike(base).memoryReservationMb(9).build());
        assertThat(base).isNotEqualTo(dockerLike(base).cpuLimit(BigDecimal.TEN).build());
        assertThat(base).isNotEqualTo(dockerLike(base).cpuShares(9).build());
        assertThat(base).isNotEqualTo(dockerLike(base).maxConcurrentContainers(9).build());
        assertThat(base).isNotEqualTo(dockerLike(base).networkMode("host").build());
        assertThat(base).isNotEqualTo(dockerLike(base).restartPolicy("always").build());
        assertThat(base).isNotEqualTo(dockerLike(base).restartMaxRetries(9).build());
        assertThat(base).isNotEqualTo(dockerLike(base).logDriver("syslog").build());
        assertThat(base).isNotEqualTo(dockerLike(base).logMaxSize("99m").build());
        assertThat(base).isNotEqualTo(dockerLike(base).logMaxFiles(9).build());
        assertThat(base).isNotEqualTo(dockerLike(base).environmentVariables("[]").build());
        assertThat(base).isNotEqualTo(dockerLike(base).labels("[]").build());
        assertThat(base).isNotEqualTo(dockerLike(base).enabled(false).build());
        assertThat(base).isNotEqualTo(dockerLike(base).createdAt(T2).build());
        assertThat(base).isNotEqualTo(dockerLike(base).updatedAt(T2).build());
        assertThat(base.hashCode()).isEqualTo(dockerLike(base).build().hashCode());
        DockerExecutionProfileEntity na = DockerExecutionProfileEntity.builder().build();
        DockerExecutionProfileEntity nb = DockerExecutionProfileEntity.builder().build();
        assertThat(na).isEqualTo(nb);
        assertThat(na.hashCode()).isEqualTo(nb.hashCode());
        na.hashCode();
    }

    @Test
    void loadTestToolEntity_equals_hashCode_explicitBranches() {
        LoadTestToolEntity base = sampleLoadTestTool();
        assertThat(base).isEqualTo(base);
        assertThat(base).isNotEqualTo(null);
        assertThat(base).isNotEqualTo(1);
        assertThat(base).isNotEqualTo(loadTestToolLike(base).id(ID2).build());
        assertThat(base).isNotEqualTo(loadTestToolLike(base).name("x").build());
        assertThat(base).isNotEqualTo(loadTestToolLike(base).dockerImage("x:2").build());
        assertThat(base).isNotEqualTo(loadTestToolLike(base).fileExtensions(List.of("py")).build());
        assertThat(base).isNotEqualTo(loadTestToolLike(base).enabled(Boolean.FALSE).build());
        assertThat(base).isNotEqualTo(loadTestToolLike(base).createdAt(T2).build());
        assertThat(base).isNotEqualTo(loadTestToolLike(base).updatedAt(T2).build());
        LoadTestToolEntity na = LoadTestToolEntity.builder().build();
        LoadTestToolEntity nb = LoadTestToolEntity.builder().build();
        assertThat(na).isEqualTo(nb);
        assertThat(na.hashCode()).isEqualTo(nb.hashCode());
        na.hashCode();
    }

    @Test
    void testMetricsEntity_equals_hashCode_explicitBranches() {
        TestMetricsEntity base = sampleMetrics();
        assertThat(base).isEqualTo(base);
        assertThat(base).isNotEqualTo(null);
        assertThat(base).isNotEqualTo(1);
        assertThat(base).isNotEqualTo(testMetricsLike(base).id(ID2).build());
        assertThat(base).isNotEqualTo(testMetricsLike(base).taskId(ID).build());
        assertThat(base).isNotEqualTo(testMetricsLike(base).sourceType("ELASTIC").build());
        assertThat(base).isNotEqualTo(testMetricsLike(base).endpointUrl("http://x").build());
        assertThat(base).isNotEqualTo(testMetricsLike(base).queryParams("q=2").build());
        assertThat(base).isNotEqualTo(testMetricsLike(base).metricsData("[]").build());
        assertThat(base).isNotEqualTo(testMetricsLike(base).collectedAt(T2).build());
        TestMetricsEntity na = TestMetricsEntity.builder().build();
        TestMetricsEntity nb = TestMetricsEntity.builder().build();
        assertThat(na).isEqualTo(nb);
        assertThat(na.hashCode()).isEqualTo(nb.hashCode());
        na.hashCode();
    }

    @Test
    void testSummaryEntity_equals_hashCode_explicitBranches() {
        TestSummaryEntity base = sampleSummary();
        assertThat(base).isEqualTo(base);
        assertThat(base).isNotEqualTo(null);
        assertThat(base).isNotEqualTo(1);
        assertThat(base).isNotEqualTo(testSummaryLike(base).id(ID2).build());
        assertThat(base).isNotEqualTo(testSummaryLike(base).taskId(ID).build());
        assertThat(base).isNotEqualTo(testSummaryLike(base).summaryType("STAT").build());
        assertThat(base).isNotEqualTo(testSummaryLike(base).summaryData("[]").build());
        assertThat(base).isNotEqualTo(testSummaryLike(base).processingStatus("PENDING").build());
        assertThat(base).isNotEqualTo(testSummaryLike(base).errorMessage("x").build());
        assertThat(base).isNotEqualTo(testSummaryLike(base).createdAt(T2).build());
        assertThat(base).isNotEqualTo(testSummaryLike(base).processedAt(T1).build());
        TestSummaryEntity na = TestSummaryEntity.builder().build();
        TestSummaryEntity nb = TestSummaryEntity.builder().build();
        assertThat(na).isEqualTo(nb);
        assertThat(na.hashCode()).isEqualTo(nb.hashCode());
        na.hashCode();
    }

    @Test
    void testTaskEntity_equals_hashCode_explicitBranches() {
        TestTaskEntity base = sampleTask();
        assertThat(base).isEqualTo(base);
        assertThat(base).isNotEqualTo(null);
        assertThat(base).isNotEqualTo(1);
        assertThat(base).isNotEqualTo(testTaskLike(base).id(ID2).build());
        assertThat(base).isNotEqualTo(testTaskLike(base).status(TestTaskStatus.PROCESSING).build());
        assertThat(base).isNotEqualTo(testTaskLike(base).createdAt(T2).build());
        assertThat(base).isNotEqualTo(testTaskLike(base).updatedAt(T2).build());
        assertThat(base).isNotEqualTo(testTaskLike(base).lockedAt(T1).build());
        assertThat(base).isNotEqualTo(testTaskLike(base).lockedBy("x").build());
        assertThat(base).isNotEqualTo(testTaskLike(base).testTool("x").build());
        assertThat(base).isNotEqualTo(testTaskLike(base).testFileName("x.js").build());
        assertThat(base).isNotEqualTo(testTaskLike(base).testFileContentBase64("WA==").build());
        assertThat(base).isNotEqualTo(testTaskLike(base).command("x").build());
        assertThat(base).isNotEqualTo(testTaskLike(base).expectedDurationSeconds(0).build());
        assertThat(base).isNotEqualTo(testTaskLike(base).metricsConfig("{}").build());
        assertThat(base).isNotEqualTo(testTaskLike(base).errorMessage("err").build());
        assertThat(base).isNotEqualTo(testTaskLike(base).summarizerName("s").build());
        assertThat(base).isNotEqualTo(testTaskLike(base).dockerExecutionProfileId(ID2).build());
        TestTaskEntity na = TestTaskEntity.builder().build();
        TestTaskEntity nb = TestTaskEntity.builder().build();
        assertThat(na).isEqualTo(nb);
        assertThat(na.hashCode()).isEqualTo(nb.hashCode());
        na.hashCode();
    }

    @Test
    void testTaskHistoryEntity_equals_hashCode_explicitBranches() {
        TestTaskHistoryEntity base = sampleHistory();
        assertThat(base).isEqualTo(base);
        assertThat(base).isNotEqualTo(null);
        assertThat(base).isNotEqualTo(1);
        assertThat(base).isNotEqualTo(testTaskHistoryLike(base).id(ID2).build());
        assertThat(base).isNotEqualTo(testTaskHistoryLike(base).finalStatus("FAIL").build());
        assertThat(base).isNotEqualTo(testTaskHistoryLike(base).createdAt(T2).build());
        assertThat(base).isNotEqualTo(testTaskHistoryLike(base).startedAt(T2).build());
        assertThat(base).isNotEqualTo(testTaskHistoryLike(base).finishedAt(T1).build());
        assertThat(base).isNotEqualTo(testTaskHistoryLike(base).movedAt(T1).build());
        assertThat(base).isNotEqualTo(testTaskHistoryLike(base).testTool("x").build());
        assertThat(base).isNotEqualTo(testTaskHistoryLike(base).testFileName("x.js").build());
        assertThat(base).isNotEqualTo(testTaskHistoryLike(base).testFileContentBase64("WA==").build());
        assertThat(base).isNotEqualTo(testTaskHistoryLike(base).command("x").build());
        assertThat(base).isNotEqualTo(testTaskHistoryLike(base).expectedDurationSeconds(0).build());
        assertThat(base).isNotEqualTo(testTaskHistoryLike(base).metricsConfig("{}").build());
        assertThat(base).isNotEqualTo(testTaskHistoryLike(base).errorMessage("err").build());
        assertThat(base).isNotEqualTo(testTaskHistoryLike(base).summarizerName("s").build());
        assertThat(base).isNotEqualTo(testTaskHistoryLike(base).dockerExecutionProfileId(ID2).build());
        assertThat(base).isNotEqualTo(testTaskHistoryLike(base).dockerProfileName("x").build());
        TestTaskHistoryEntity na = TestTaskHistoryEntity.builder().build();
        TestTaskHistoryEntity nb = TestTaskHistoryEntity.builder().build();
        assertThat(na).isEqualTo(nb);
        assertThat(na.hashCode()).isEqualTo(nb.hashCode());
        na.hashCode();
    }

    private static DockerExecutionProfileEntity.DockerExecutionProfileEntityBuilder dockerLike(DockerExecutionProfileEntity e) {
        return DockerExecutionProfileEntity.builder()
                .id(e.getId())
                .name(e.getName())
                .dockerHostUri(e.getDockerHostUri())
                .namedVolumeForChildBinds(e.getNamedVolumeForChildBinds())
                .memoryLimitMb(e.getMemoryLimitMb())
                .memoryReservationMb(e.getMemoryReservationMb())
                .cpuLimit(e.getCpuLimit())
                .cpuShares(e.getCpuShares())
                .maxConcurrentContainers(e.getMaxConcurrentContainers())
                .networkMode(e.getNetworkMode())
                .restartPolicy(e.getRestartPolicy())
                .restartMaxRetries(e.getRestartMaxRetries())
                .logDriver(e.getLogDriver())
                .logMaxSize(e.getLogMaxSize())
                .logMaxFiles(e.getLogMaxFiles())
                .environmentVariables(e.getEnvironmentVariables())
                .labels(e.getLabels())
                .enabled(e.isEnabled())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt());
    }

    private static LoadTestToolEntity.LoadTestToolEntityBuilder loadTestToolLike(LoadTestToolEntity e) {
        return LoadTestToolEntity.builder()
                .id(e.getId())
                .name(e.getName())
                .dockerImage(e.getDockerImage())
                .fileExtensions(e.getFileExtensions())
                .enabled(e.getEnabled())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt());
    }

    private static TestMetricsEntity.TestMetricsEntityBuilder testMetricsLike(TestMetricsEntity e) {
        return TestMetricsEntity.builder()
                .id(e.getId())
                .taskId(e.getTaskId())
                .sourceType(e.getSourceType())
                .endpointUrl(e.getEndpointUrl())
                .queryParams(e.getQueryParams())
                .metricsData(e.getMetricsData())
                .collectedAt(e.getCollectedAt());
    }

    private static TestSummaryEntity.TestSummaryEntityBuilder testSummaryLike(TestSummaryEntity e) {
        return TestSummaryEntity.builder()
                .id(e.getId())
                .taskId(e.getTaskId())
                .summaryType(e.getSummaryType())
                .summaryData(e.getSummaryData())
                .processingStatus(e.getProcessingStatus())
                .errorMessage(e.getErrorMessage())
                .createdAt(e.getCreatedAt())
                .processedAt(e.getProcessedAt());
    }

    private static TestTaskEntity.TestTaskEntityBuilder testTaskLike(TestTaskEntity e) {
        return TestTaskEntity.builder()
                .id(e.getId())
                .status(e.getStatus())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .lockedAt(e.getLockedAt())
                .lockedBy(e.getLockedBy())
                .testTool(e.getTestTool())
                .testFileName(e.getTestFileName())
                .testFileContentBase64(e.getTestFileContentBase64())
                .command(e.getCommand())
                .expectedDurationSeconds(e.getExpectedDurationSeconds())
                .metricsConfig(e.getMetricsConfig())
                .errorMessage(e.getErrorMessage())
                .summarizerName(e.getSummarizerName())
                .dockerExecutionProfileId(e.getDockerExecutionProfileId());
    }

    private static TestTaskHistoryEntity.TestTaskHistoryEntityBuilder testTaskHistoryLike(TestTaskHistoryEntity e) {
        return TestTaskHistoryEntity.builder()
                .id(e.getId())
                .finalStatus(e.getFinalStatus())
                .createdAt(e.getCreatedAt())
                .startedAt(e.getStartedAt())
                .finishedAt(e.getFinishedAt())
                .movedAt(e.getMovedAt())
                .testTool(e.getTestTool())
                .testFileName(e.getTestFileName())
                .testFileContentBase64(e.getTestFileContentBase64())
                .command(e.getCommand())
                .expectedDurationSeconds(e.getExpectedDurationSeconds())
                .metricsConfig(e.getMetricsConfig())
                .errorMessage(e.getErrorMessage())
                .summarizerName(e.getSummarizerName())
                .dockerExecutionProfileId(e.getDockerExecutionProfileId())
                .dockerProfileName(e.getDockerProfileName());
    }

    private static DockerExecutionProfileEntity sampleDocker() {
        return DockerExecutionProfileEntity.builder()
                .id(ID)
                .name("n")
                .dockerHostUri("http://h")
                .namedVolumeForChildBinds("v")
                .memoryLimitMb(1)
                .memoryReservationMb(2)
                .cpuLimit(BigDecimal.ONE)
                .cpuShares(3)
                .maxConcurrentContainers(2)
                .networkMode("bridge")
                .restartPolicy("no")
                .restartMaxRetries(0)
                .logDriver("json-file")
                .logMaxSize("10m")
                .logMaxFiles(2)
                .environmentVariables("{}")
                .labels("{}")
                .enabled(true)
                .createdAt(T1)
                .updatedAt(T1)
                .build();
    }

    private static LoadTestToolEntity sampleLoadTestTool() {
        return LoadTestToolEntity.builder()
                .id(ID)
                .name("tool")
                .dockerImage("img:1")
                .fileExtensions(List.of("js"))
                .enabled(Boolean.TRUE)
                .createdAt(T1)
                .updatedAt(T1)
                .build();
    }

    private static TestArtifactEntity.TestArtifactEntityBuilder sampleArtifactBuilder() {
        return TestArtifactEntity.builder()
                .id(ID)
                .taskId(ID2)
                .fileName("r.html")
                .fileContent(new byte[] {1, 2})
                .contentEncoding("gzip")
                .originalSizeBytes(10L)
                .compressedSizeBytes(5L)
                .createdAt(T1);
    }

    private static TestArtifactEntity sampleArtifact() {
        return sampleArtifactBuilder().build();
    }

    private static TestMetricsEntity sampleMetrics() {
        return TestMetricsEntity.builder()
                .id(ID)
                .taskId(ID2)
                .sourceType("PROMETHEUS")
                .endpointUrl("http://m")
                .queryParams("q=1")
                .metricsData("{}")
                .collectedAt(T1)
                .build();
    }

    private static TestSummaryEntity sampleSummary() {
        return TestSummaryEntity.builder()
                .id(ID)
                .taskId(ID2)
                .summaryType("AI")
                .summaryData("{}")
                .processingStatus("DONE")
                .errorMessage("e")
                .createdAt(T1)
                .processedAt(T2)
                .build();
    }

    private static TestTaskEntity sampleTask() {
        return TestTaskEntity.builder()
                .id(ID)
                .status(TestTaskStatus.PENDING)
                .createdAt(T1)
                .updatedAt(T1)
                .lockedAt(T2)
                .lockedBy("host")
                .testTool("k6")
                .testFileName("t.js")
                .testFileContentBase64("YQ==")
                .command("run")
                .expectedDurationSeconds(60)
                .metricsConfig(null)
                .errorMessage(null)
                .summarizerName(null)
                .dockerExecutionProfileId(PROFILE_ID)
                .build();
    }

    private static TestTaskHistoryEntity sampleHistory() {
        return TestTaskHistoryEntity.builder()
                .id(ID)
                .finalStatus("OK")
                .createdAt(T1)
                .startedAt(T1)
                .finishedAt(T2)
                .movedAt(T2)
                .testTool("k6")
                .testFileName("t.js")
                .testFileContentBase64("YQ==")
                .command("run")
                .expectedDurationSeconds(60)
                .metricsConfig(null)
                .errorMessage(null)
                .summarizerName(null)
                .dockerExecutionProfileId(PROFILE_ID)
                .dockerProfileName("p1")
                .build();
    }

    private static void assertCanEqualBranches(Class<?> type) throws Exception {
        Object left = newInstance(type);
        Object right = newInstance(type);
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(left, "canEqual", right)).isTrue();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(left, "canEqual", "x")).isFalse();
    }

    private static void assertNullFieldBranches(Class<?> type) throws Exception {
        Object base = newInstance(type);
        setAllFields(base, 1);
        for (Field f : instanceFields(type)) {
            if (f.getType().isPrimitive()) {
                continue;
            }
            Object leftNull = cloneWithAllFields(type, base);
            Object rightValue = cloneWithAllFields(type, base);
            f.set(leftNull, null);
            assertThat(leftNull).isNotEqualTo(rightValue);

            Object leftValue = cloneWithAllFields(type, base);
            Object rightNull = cloneWithAllFields(type, base);
            f.set(rightNull, null);
            assertThat(leftValue).isNotEqualTo(rightNull);
        }
    }

    private static void assertEqualsSelfAndHashCodeNullBranches(Class<?> type) throws Exception {
        Object base = newInstance(type);
        setAllFields(base, 1);
        assertThat(base).isEqualTo(base);
        base.hashCode();
        for (Field f : instanceFields(type)) {
            if (f.getType().isPrimitive()) {
                continue;
            }
            Object withNull = cloneWithAllFields(type, base);
            f.set(withNull, null);
            withNull.hashCode();
        }
    }

    private static void assertEqualsWithBothNullFieldValues(Class<?> type) throws Exception {
        Object left = newInstance(type);
        Object right = newInstance(type);
        setAllFields(left, 1);
        setAllFields(right, 1);
        for (Field f : instanceFields(type)) {
            if (!f.getType().isPrimitive()) {
                f.set(left, null);
                f.set(right, null);
            }
        }
        assertThat(left).isEqualTo(right);
        assertThat(left.hashCode()).isEqualTo(right.hashCode());
    }

    private static Object newInstance(Class<?> type) throws Exception {
        try {
            var ctor = type.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (NoSuchMethodException e) {
            List<Field> fs = instanceFields(type);
            Class<?>[] sig = fs.stream().map(Field::getType).toArray(Class[]::new);
            Object[] args = fs.stream().map(f -> valueForField(f.getType(), f.getName(), 1)).toArray();
            var ctor = type.getDeclaredConstructor(sig);
            ctor.setAccessible(true);
            return ctor.newInstance(args);
        }
    }

    private static List<Field> instanceFields(Class<?> type) {
        List<Field> out = new ArrayList<>();
        for (Field f : type.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) && !f.isSynthetic()) {
                f.setAccessible(true);
                out.add(f);
            }
        }
        return out;
    }

    private static void setAllFields(Object target, int seed) throws Exception {
        for (Field f : instanceFields(target.getClass())) {
            f.set(target, valueForField(f.getType(), f.getName(), seed));
        }
    }

    private static Object cloneWithAllFields(Class<?> type, Object source) throws Exception {
        Object copy = newInstance(type);
        for (Field f : instanceFields(type)) {
            f.set(copy, f.get(source));
        }
        return copy;
    }

    private static Object valueForField(Class<?> t, String name, int seed) {
        if (t == String.class) return name + "-" + seed;
        if (t == Integer.class || t == int.class) return 10 + seed;
        if (t == Long.class || t == long.class) return 100L + seed;
        if (t == BigDecimal.class) return BigDecimal.valueOf(10 + seed);
        if (t == Double.class || t == double.class) return 1.0 + seed;
        if (t == Float.class || t == float.class) return 1.0f + seed;
        if (t == Short.class || t == short.class) return (short) (10 + seed);
        if (t == Byte.class || t == byte.class) return (byte) (10 + seed);
        if (t == Boolean.class || t == boolean.class) return seed % 2 == 0;
        if (t == UUID.class) return UUID.nameUUIDFromBytes((name + "-" + seed).getBytes());
        if (t == OffsetDateTime.class) {
            return OffsetDateTime.parse(seed == 1 ? "2024-01-01T00:00:00Z" : "2024-01-02T00:00:00Z");
        }
        if (t == byte[].class) return seed == 1 ? new byte[] {1} : new byte[] {2};
        if (List.class.isAssignableFrom(t)) {
            return seed == 1 ? List.of("a") : List.of("b");
        }
        if (Map.class.isAssignableFrom(t)) {
            return seed == 1 ? Map.of("k", "v") : Map.of("k2", "v2");
        }
        if (t.isEnum()) {
            Object[] c = t.getEnumConstants();
            return c[(seed - 1) % c.length];
        }
        try {
            var ctor = t.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (ReflectiveOperationException ignored) {
            return seed == 1 ? new LinkedHashMap<>() : new ArrayList<>();
        }
    }

    private static final class DockerExecutionProfileEntityChild extends DockerExecutionProfileEntity {}

    private static final class DockerExecutionProfileEntityCanEqualFalse extends DockerExecutionProfileEntity {
        @Override
        protected boolean canEqual(Object other) {
            return false;
        }
    }

    private static final class LoadTestToolEntityChild extends LoadTestToolEntity {}

    private static final class LoadTestToolEntityCanEqualFalse extends LoadTestToolEntity {
        @Override
        protected boolean canEqual(Object other) {
            return false;
        }
    }

    private static final class TestArtifactEntityChild extends TestArtifactEntity {}

    private static final class TestArtifactEntityCanEqualFalse extends TestArtifactEntity {
        @Override
        protected boolean canEqual(Object other) {
            return false;
        }
    }

    private static final class TestMetricsEntityChild extends TestMetricsEntity {}

    private static final class TestMetricsEntityCanEqualFalse extends TestMetricsEntity {
        @Override
        protected boolean canEqual(Object other) {
            return false;
        }
    }

    private static final class TestSummaryEntityChild extends TestSummaryEntity {}

    private static final class TestSummaryEntityCanEqualFalse extends TestSummaryEntity {
        @Override
        protected boolean canEqual(Object other) {
            return false;
        }
    }

    private static final class TestTaskEntityChild extends TestTaskEntity {}

    private static final class TestTaskEntityCanEqualFalse extends TestTaskEntity {
        @Override
        protected boolean canEqual(Object other) {
            return false;
        }
    }

    private static final class TestTaskHistoryEntityChild extends TestTaskHistoryEntity {}

    private static final class TestTaskHistoryEntityCanEqualFalse extends TestTaskHistoryEntity {
        @Override
        protected boolean canEqual(Object other) {
            return false;
        }
    }
}
