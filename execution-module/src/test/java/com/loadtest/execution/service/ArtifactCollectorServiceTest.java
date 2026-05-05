package com.loadtest.execution.service;

import com.loadtest.execution.persistence.TestArtifactRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ArtifactCollectorServiceTest {

    @Mock private TestArtifactRepository artifactRepository;
    @Mock private CommandFromDbService commandFromDbService;

    @InjectMocks private ArtifactCollectorService collector;

    @Test
    void collect_skipsWhenTaskIdNull() {
        collector.collectAndSaveArtifacts(null, "cmd {reportBaseName}.html", Map.of("reportBaseName", "r"));
        verify(artifactRepository, never()).save(any());
    }

    @Test
    void collect_skipsWhenPlaceholdersNull() {
        collector.collectAndSaveArtifacts(UUID.randomUUID(), "cmd", null);
        verify(artifactRepository, never()).save(any());
    }

    @Test
    void collect_skipsWhenCommandBlank() {
        collector.collectAndSaveArtifacts(UUID.randomUUID(), "  ", Map.of());
        verify(artifactRepository, never()).save(any());
    }

    @Test
    void collect_skipsWhenCommandNull() {
        UUID taskId = UUID.randomUUID();
        collector.collectAndSaveArtifacts(taskId, null, Map.of("reportBaseName", "x"));
        verify(artifactRepository, never()).save(any());
    }

    @Test
    void collect_skipsWhenNoReportOrMetricsPlaceholders() {
        collector.collectAndSaveArtifacts(UUID.randomUUID(), "run tool", Map.of("reportBaseName", "x"));
        verify(artifactRepository, never()).save(any());
    }

    @Test
    void collect_savesWhenHostFileExists(@TempDir Path reports) throws Exception {
        UUID taskId = UUID.randomUUID();
        String reportBase = "rep1";
        Files.createDirectories(reports);
        Path hostFile = reports.resolve(reportBase + ".txt");
        Files.writeString(hostFile, "data");
        when(commandFromDbService.deriveArtifactFilePathsFromCommand(any(), any()))
                .thenReturn(java.util.List.of(hostFile.toString()));

        collector.collectAndSaveArtifacts(
                taskId,
                "--out {reportBaseName}.txt",
                Map.of(
                        "reportBaseName", reportBase,
                        "metricsBaseName", reportBase,
                        "reportsHostPath", reports.toAbsolutePath().toString(),
                        "metricsHostPath", reports.toAbsolutePath().toString()
                ));

        verify(artifactRepository).save(any());
    }

    @Test
    void collect_throwsWhenPlaceholdersPresentButNoFiles(@TempDir Path tmp) {
        UUID taskId = UUID.randomUUID();
        when(commandFromDbService.deriveArtifactFilePathsFromCommand(any(), any())).thenReturn(Collections.emptyList());
        assertThatThrownBy(() -> collector.collectAndSaveArtifacts(
                taskId,
                "--out {reportBaseName}.html",
                Map.of(
                        "reportBaseName", "missing",
                        "metricsBaseName", "missing",
                        "reportsHostPath", tmp.toAbsolutePath().toString(),
                        "metricsHostPath", tmp.toAbsolutePath().toString()
                )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("пути к файлам");
    }

    @Test
    void collect_skipsNullPathFromDerive(@TempDir Path reports) throws Exception {
        UUID taskId = UUID.randomUUID();
        String reportBase = "nullp";
        Files.createDirectories(reports);
        Path real = reports.resolve(reportBase + ".dat");
        Files.writeString(real, "ok");
        when(commandFromDbService.deriveArtifactFilePathsFromCommand(any(), any()))
                .thenReturn(Arrays.asList(null, "  "));

        collector.collectAndSaveArtifacts(
                taskId,
                "--out {reportBaseName}.dat",
                Map.of(
                        "reportBaseName", reportBase,
                        "metricsBaseName", reportBase,
                        "reportsHostPath", reports.toAbsolutePath().toString(),
                        "metricsHostPath", reports.toAbsolutePath().toString()));

        verify(artifactRepository).save(any());
    }

    @Test
    void collect_skipsBlankPathsFromDerive(@TempDir Path reports) throws Exception {
        UUID taskId = UUID.randomUUID();
        String reportBase = "rb";
        Files.createDirectories(reports);
        Path real = reports.resolve(reportBase + ".dat");
        Files.writeString(real, "ok");
        when(commandFromDbService.deriveArtifactFilePathsFromCommand(any(), any()))
                .thenReturn(List.of("  ", "", real.toString()));

        collector.collectAndSaveArtifacts(
                taskId,
                "--out {reportBaseName}.dat",
                Map.of(
                        "reportBaseName", reportBase,
                        "metricsBaseName", reportBase,
                        "reportsHostPath", reports.toAbsolutePath().toString(),
                        "metricsHostPath", reports.toAbsolutePath().toString()
                ));

        verify(artifactRepository).save(any());
    }

    @Test
    void collect_missingFileDoesNotSave(@TempDir Path reports) throws Exception {
        UUID taskId = UUID.randomUUID();
        String reportBase = "gone";
        Files.createDirectories(reports);
        Path ghost = reports.resolve(reportBase + ".missing");
        when(commandFromDbService.deriveArtifactFilePathsFromCommand(any(), any()))
                .thenReturn(List.of(ghost.toString()));

        collector.collectAndSaveArtifacts(
                taskId,
                "--out {reportBaseName}.missing",
                Map.of(
                        "reportBaseName", reportBase,
                        "metricsBaseName", reportBase,
                        "reportsHostPath", reports.toAbsolutePath().toString(),
                        "metricsHostPath", reports.toAbsolutePath().toString()
                ));

        verify(artifactRepository, never()).save(any());
    }

    @Test
    void collect_saveFailureIsLogged(@TempDir Path reports) throws Exception {
        UUID taskId = UUID.randomUUID();
        String reportBase = "savefail";
        Files.createDirectories(reports);
        Path f = reports.resolve(reportBase + ".txt");
        Files.writeString(f, "data");
        when(commandFromDbService.deriveArtifactFilePathsFromCommand(any(), any()))
                .thenReturn(List.of(f.toString()));
        doThrow(new RuntimeException("db-down")).when(artifactRepository).save(any());

        collector.collectAndSaveArtifacts(
                taskId,
                "--out {reportBaseName}.txt",
                Map.of(
                        "reportBaseName", reportBase,
                        "metricsBaseName", reportBase,
                        "reportsHostPath", reports.toAbsolutePath().toString(),
                        "metricsHostPath", reports.toAbsolutePath().toString()
                ));

        verify(artifactRepository).save(any());
    }

    @Test
    void collect_addsMatchingPrefixFilesWhenDeriveEmpty(@TempDir Path reports) throws Exception {
        UUID taskId = UUID.randomUUID();
        String reportBase = "prefix1";
        Files.createDirectories(reports);
        Files.writeString(reports.resolve(reportBase + ".csv"), "a");
        when(commandFromDbService.deriveArtifactFilePathsFromCommand(any(), any()))
                .thenReturn(Collections.emptyList());

        collector.collectAndSaveArtifacts(
                taskId,
                "{reportBaseName}.csv",
                Map.of(
                        "reportBaseName", reportBase,
                        "metricsBaseName", reportBase,
                        "reportsHostPath", reports.toAbsolutePath().toString(),
                        "metricsHostPath", reports.toAbsolutePath().toString()
                ));

        verify(artifactRepository).save(any());
    }

    @Test
    void gzip_handlesNullAndEmpty() throws Exception {
        Method gzip = ArtifactCollectorService.class.getDeclaredMethod("gzip", byte[].class);
        gzip.setAccessible(true);
        assertThat((byte[]) gzip.invoke(null, new Object[] {null})).isEmpty();
        assertThat((byte[]) gzip.invoke(null, new Object[] {new byte[0]})).isEmpty();
        assertThat((byte[]) gzip.invoke(null, new Object[] {new byte[] {1, 2, 3}})).isNotEmpty();
    }

    @Test
    void addFilesByPrefix_earlyExit_viaReflection() throws Exception {
        Method m = ArtifactCollectorService.class.getDeclaredMethod(
                "addFilesByPrefix", java.util.Set.class, String.class, String.class);
        m.setAccessible(true);
        java.util.Set<String> set = new java.util.LinkedHashSet<>();
        m.invoke(null, set, null, "p");
        m.invoke(null, set, "", "p");
        m.invoke(null, set, "   ", "p");
        m.invoke(null, set, "/tmp", null);
        m.invoke(null, set, "/tmp", "");
        assertThat(set).isEmpty();
    }

    @Test
    void deleteRemainingFilesByPrefix_earlyExit_viaReflection() throws Exception {
        Method m = ArtifactCollectorService.class.getDeclaredMethod("deleteRemainingFilesByPrefix", String.class, String.class);
        m.setAccessible(true);
        m.invoke(null, null, "p");
        m.invoke(null, "", "p");
        m.invoke(null, "   ", "p");
        m.invoke(null, "/tmp", null);
        m.invoke(null, "/tmp", "");
    }

    @Test
    void addFilesByPrefix_skipsWhenPathIsNotDirectory(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("notadir.txt");
        Files.writeString(file, "x");
        Method m = ArtifactCollectorService.class.getDeclaredMethod("addFilesByPrefix", Set.class, String.class, String.class);
        m.setAccessible(true);
        Set<String> set = new LinkedHashSet<>();
        m.invoke(null, set, file.toAbsolutePath().toString(), "pfx");
        assertThat(set).isEmpty();
    }

    @Test
    void addFilesByPrefix_successListsRegularFiles(@TempDir Path reports) throws Exception {
        Files.createDirectories(reports);
        String prefix = "okpre";
        Path match = reports.resolve(prefix + "_out.log");
        Files.writeString(match, "log");
        Files.writeString(reports.resolve("other.txt"), "no");
        Method m = ArtifactCollectorService.class.getDeclaredMethod("addFilesByPrefix", Set.class, String.class, String.class);
        m.setAccessible(true);
        Set<String> set = new LinkedHashSet<>();
        m.invoke(null, set, reports.toAbsolutePath().toString(), prefix);
        assertThat(set).containsExactly(match.toAbsolutePath().normalize().toString());
    }

    @Test
    void deleteRemainingFilesByPrefix_skipsWhenPathIsNotDirectory(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("notadir.bin");
        Files.writeString(file, "x");
        Method m = ArtifactCollectorService.class.getDeclaredMethod("deleteRemainingFilesByPrefix", String.class, String.class);
        m.setAccessible(true);
        m.invoke(null, file.toAbsolutePath().toString(), "pfx");
        assertThat(Files.exists(file)).isTrue();
    }

    @Test
    void deleteRemainingFilesByPrefix_successListsAndDeletes(@TempDir Path reports) throws Exception {
        Files.createDirectories(reports);
        String prefix = "rmme";
        Path toDelete = reports.resolve(prefix + "_left.csv");
        Files.writeString(toDelete, "d");
        Method m = ArtifactCollectorService.class.getDeclaredMethod("deleteRemainingFilesByPrefix", String.class, String.class);
        m.setAccessible(true);
        m.invoke(null, reports.toAbsolutePath().toString(), prefix);
        assertThat(Files.exists(toDelete)).isFalse();
    }

    @Test
    void deleteRemainingFilesByPrefix_emptyDirectory_completesTryWithResources(@TempDir Path reports) throws Exception {
        Files.createDirectories(reports);
        Method m = ArtifactCollectorService.class.getDeclaredMethod("deleteRemainingFilesByPrefix", String.class, String.class);
        m.setAccessible(true);
        m.invoke(null, reports.toAbsolutePath().toString(), "any");
    }

    @Test
    void addFilesByPrefix_emptyDirectory_completesTryWithResources(@TempDir Path reports) throws Exception {
        Files.createDirectories(reports);
        Method m = ArtifactCollectorService.class.getDeclaredMethod("addFilesByPrefix", Set.class, String.class, String.class);
        m.setAccessible(true);
        Set<String> set = new LinkedHashSet<>();
        m.invoke(null, set, reports.toAbsolutePath().toString(), "nop");
        assertThat(set).isEmpty();
    }

    @Test
    void collect_warnsWhenDeleteAfterSaveFails(@TempDir Path reports) throws Exception {
        UUID taskId = UUID.randomUUID();
        String reportBase = "postdel";
        Files.createDirectories(reports);
        Path hostFile = reports.resolve(reportBase + ".txt");
        Files.writeString(hostFile, "data");
        when(commandFromDbService.deriveArtifactFilePathsFromCommand(any(), any()))
                .thenReturn(List.of(hostFile.toString()));

        try (MockedStatic<Files> files = Mockito.mockStatic(Files.class, Mockito.withSettings().defaultAnswer(Mockito.CALLS_REAL_METHODS))) {
            files.when(() -> Files.delete(any(Path.class))).thenThrow(new IOException("in use"));
            collector.collectAndSaveArtifacts(
                    taskId,
                    "--out {reportBaseName}.txt",
                    Map.of(
                            "reportBaseName", reportBase,
                            "metricsBaseName", reportBase,
                            "reportsHostPath", reports.toAbsolutePath().toString(),
                            "metricsHostPath", reports.toAbsolutePath().toString()));
        }

        verify(artifactRepository).save(any());
        assertThat(Files.exists(hostFile)).isTrue();
    }

    @Test
    void addFilesByPrefix_listIOException_logsDebug(@TempDir Path reports) throws Exception {
        Files.createDirectories(reports);
        Method m = ArtifactCollectorService.class.getDeclaredMethod(
                "addFilesByPrefix", Set.class, String.class, String.class);
        m.setAccessible(true);
        Set<String> set = new LinkedHashSet<>();
        try (MockedStatic<Files> files = Mockito.mockStatic(Files.class, Mockito.withSettings().defaultAnswer(Mockito.CALLS_REAL_METHODS))) {
            files.when(() -> Files.list(any(Path.class))).thenThrow(new IOException("list denied"));
            m.invoke(null, set, reports.toAbsolutePath().toString(), "pfx");
        }
        assertThat(set).isEmpty();
    }

    @Test
    void deleteRemainingFilesByPrefix_deleteIOException_logsWarn(@TempDir Path reports) throws Exception {
        Files.createDirectories(reports);
        Path leftover = reports.resolve("pfx_warn.bin");
        Files.writeString(leftover, "x");
        Method m = ArtifactCollectorService.class.getDeclaredMethod("deleteRemainingFilesByPrefix", String.class, String.class);
        m.setAccessible(true);
        try (MockedStatic<Files> files = Mockito.mockStatic(Files.class, Mockito.withSettings().defaultAnswer(Mockito.CALLS_REAL_METHODS))) {
            files.when(() -> Files.delete(any(Path.class))).thenThrow(new IOException("cannot delete"));
            m.invoke(null, reports.toAbsolutePath().toString(), "pfx");
        }
        assertThat(Files.exists(leftover)).isTrue();
    }

    @Test
    void deleteRemainingFilesByPrefix_listIOException_logsDebug(@TempDir Path reports) throws Exception {
        Files.createDirectories(reports);
        Method m = ArtifactCollectorService.class.getDeclaredMethod("deleteRemainingFilesByPrefix", String.class, String.class);
        m.setAccessible(true);
        try (MockedStatic<Files> files = Mockito.mockStatic(Files.class, Mockito.withSettings().defaultAnswer(Mockito.CALLS_REAL_METHODS))) {
            files.when(() -> Files.list(any(Path.class))).thenThrow(new IOException("list failed"));
            m.invoke(null, reports.toAbsolutePath().toString(), "pfx");
        }
    }

    @Test
    void deleteRemainingFilesByPrefix_forEachDeleteThrowsUnchecked_twrClosesStream(@TempDir Path reports) throws Exception {
        Files.createDirectories(reports);
        Path f = Files.createFile(reports.resolve("pfx_rt.bin"));
        Files.writeString(f, "x");
        Method m = ArtifactCollectorService.class.getDeclaredMethod("deleteRemainingFilesByPrefix", String.class, String.class);
        m.setAccessible(true);
        try (MockedStatic<Files> files = Mockito.mockStatic(Files.class, Mockito.withSettings().defaultAnswer(Mockito.CALLS_REAL_METHODS))) {
            files.when(() -> Files.delete(any(Path.class))).thenThrow(new RuntimeException("del-rt"));
            assertThatThrownBy(() -> m.invoke(null, reports.toAbsolutePath().toString(), "pfx"))
                    .isInstanceOf(InvocationTargetException.class)
                    .hasCauseInstanceOf(RuntimeException.class)
                    .cause()
                    .hasMessageContaining("del-rt");
        }
    }

    @Test
    void addFilesByPrefix_forEachAddThrowsUnchecked_twrClosesStream(@TempDir Path reports) throws Exception {
        Files.createDirectories(reports);
        Path f = Files.createFile(reports.resolve("pfx_add_rt.bin"));
        Files.writeString(f, "x");
        Method m = ArtifactCollectorService.class.getDeclaredMethod("addFilesByPrefix", Set.class, String.class, String.class);
        m.setAccessible(true);
        Set<String> set = new LinkedHashSet<>() {
            @Override
            public boolean add(String e) {
                throw new RuntimeException("add-rt");
            }
        };
        assertThatThrownBy(() -> m.invoke(null, set, reports.toAbsolutePath().toString(), "pfx"))
                .isInstanceOf(InvocationTargetException.class)
                .hasCauseInstanceOf(RuntimeException.class)
                .cause()
                .hasMessageContaining("add-rt");
    }

    @Test
    @SuppressWarnings("unchecked")
    void deleteRemainingFilesByPrefix_streamCloseThrows_outerCatch(@TempDir Path reports) throws Exception {
        Files.createDirectories(reports);
        Path f = Files.createFile(reports.resolve("pfx_close.bin"));
        Files.writeString(f, "x");
        Stream<Path> listStream = mock(Stream.class);
        when(listStream.filter(any())).thenAnswer(inv -> Stream.of(f).filter(inv.getArgument(0)));
        doThrow(new IOException("close-fail")).when(listStream).close();

        Method m = ArtifactCollectorService.class.getDeclaredMethod("deleteRemainingFilesByPrefix", String.class, String.class);
        m.setAccessible(true);
        try (MockedStatic<Files> files = Mockito.mockStatic(Files.class, Mockito.withSettings().defaultAnswer(Mockito.CALLS_REAL_METHODS))) {
            files.when(() -> Files.list(any(Path.class))).thenReturn(listStream);
            m.invoke(null, reports.toAbsolutePath().toString(), "pfx");
        }
        assertThat(Files.exists(f)).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void deleteRemainingFilesByPrefix_streamCloseThrowsRuntime_propagates(@TempDir Path reports) throws Exception {
        Files.createDirectories(reports);
        Path f = Files.createFile(reports.resolve("pfx_close_rt.bin"));
        Files.writeString(f, "x");
        Stream<Path> listStream = mock(Stream.class);
        when(listStream.filter(any())).thenAnswer(inv -> Stream.of(f).filter(inv.getArgument(0)));
        doThrow(new RuntimeException("close-rt")).when(listStream).close();

        Method m = ArtifactCollectorService.class.getDeclaredMethod("deleteRemainingFilesByPrefix", String.class, String.class);
        m.setAccessible(true);
        try (MockedStatic<Files> files = Mockito.mockStatic(Files.class, Mockito.withSettings().defaultAnswer(Mockito.CALLS_REAL_METHODS))) {
            files.when(() -> Files.list(any(Path.class))).thenReturn(listStream);
            assertThatThrownBy(() -> m.invoke(null, reports.toAbsolutePath().toString(), "pfx"))
                    .isInstanceOf(InvocationTargetException.class)
                    .hasCauseInstanceOf(RuntimeException.class)
                    .cause()
                    .hasMessageContaining("close-rt");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void addFilesByPrefix_streamCloseThrows_outerCatch(@TempDir Path reports) throws Exception {
        Files.createDirectories(reports);
        Path f = Files.createFile(reports.resolve("pfx_add_close.bin"));
        Files.writeString(f, "x");
        Stream<Path> listStream = mock(Stream.class);
        when(listStream.filter(any())).thenAnswer(inv -> Stream.of(f).filter(inv.getArgument(0)));
        doThrow(new IOException("close-fail")).when(listStream).close();

        Method m = ArtifactCollectorService.class.getDeclaredMethod("addFilesByPrefix", Set.class, String.class, String.class);
        m.setAccessible(true);
        Set<String> set = new LinkedHashSet<>();
        try (MockedStatic<Files> files = Mockito.mockStatic(Files.class, Mockito.withSettings().defaultAnswer(Mockito.CALLS_REAL_METHODS))) {
            files.when(() -> Files.list(any(Path.class))).thenReturn(listStream);
            m.invoke(null, set, reports.toAbsolutePath().toString(), "pfx");
        }
        assertThat(set).containsExactly(f.toAbsolutePath().normalize().toString());
        assertThat(Files.exists(f)).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void addFilesByPrefix_streamCloseThrowsRuntime_propagates(@TempDir Path reports) throws Exception {
        Files.createDirectories(reports);
        Path f = Files.createFile(reports.resolve("pfx_add_close_rt.bin"));
        Files.writeString(f, "x");
        Stream<Path> listStream = mock(Stream.class);
        when(listStream.filter(any())).thenAnswer(inv -> Stream.of(f).filter(inv.getArgument(0)));
        doThrow(new RuntimeException("close-rt-add")).when(listStream).close();

        Method m = ArtifactCollectorService.class.getDeclaredMethod("addFilesByPrefix", Set.class, String.class, String.class);
        m.setAccessible(true);
        Set<String> set = new LinkedHashSet<>();
        try (MockedStatic<Files> files = Mockito.mockStatic(Files.class, Mockito.withSettings().defaultAnswer(Mockito.CALLS_REAL_METHODS))) {
            files.when(() -> Files.list(any(Path.class))).thenReturn(listStream);
            assertThatThrownBy(() -> m.invoke(null, set, reports.toAbsolutePath().toString(), "pfx"))
                    .isInstanceOf(InvocationTargetException.class)
                    .hasCauseInstanceOf(RuntimeException.class)
                    .cause()
                    .hasMessageContaining("close-rt-add");
        }
    }

    @Test
    void deleteRemainingFilesByPrefix_listSucceeds_noMatchingRegularFiles(@TempDir Path reports) throws Exception {
        Files.createDirectories(reports);
        Path nested = reports.resolve("pfx_nested");
        Files.createDirectories(nested);
        Files.writeString(nested.resolve("pfx_inner.bin"), "x");
        Method m = ArtifactCollectorService.class.getDeclaredMethod("deleteRemainingFilesByPrefix", String.class, String.class);
        m.setAccessible(true);
        m.invoke(null, reports.toAbsolutePath().toString(), "pfx");
        assertThat(Files.exists(nested)).isTrue();
    }

    @Test
    void addFilesByPrefix_listSucceeds_noMatchingRegularFiles(@TempDir Path reports) throws Exception {
        Files.createDirectories(reports);
        Path nested = reports.resolve("pfx_nested_add");
        Files.createDirectories(nested);
        Files.writeString(nested.resolve("pfx_inner_add.bin"), "x");
        Method m = ArtifactCollectorService.class.getDeclaredMethod("addFilesByPrefix", Set.class, String.class, String.class);
        m.setAccessible(true);
        Set<String> set = new LinkedHashSet<>();
        m.invoke(null, set, reports.toAbsolutePath().toString(), "pfx");
        assertThat(set).isEmpty();
    }
}
