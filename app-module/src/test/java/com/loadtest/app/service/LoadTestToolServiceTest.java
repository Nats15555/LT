package com.loadtest.app.service;

import com.loadtest.app.dto.CreateLoadTestToolRequest;
import com.loadtest.app.dto.UpdateLoadTestToolRequest;
import com.loadtest.app.persistence.LoadTestToolEntity;
import com.loadtest.app.persistence.LoadTestToolRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoadTestToolServiceTest {

    @Mock
    private LoadTestToolRepository toolRepository;
    @Mock
    private EntityManager entityManager;

    private LoadTestToolService service;

    @BeforeEach
    void setUp() {
        service = new LoadTestToolService(toolRepository, entityManager);
    }

    private Query mockChainedNativeQuery() {
        Query q = mock(Query.class);
        lenient().when(q.setParameter(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(q);
        return q;
    }

    @Test
    void createTool_duplicateName() {
        when(toolRepository.existsByName("k6")).thenReturn(true);
        CreateLoadTestToolRequest req = CreateLoadTestToolRequest.builder()
                .name("k6")
                .dockerImage("img")
                .fileExtensions(List.of(".js"))
                .enabled(true)
                .build();
        assertThatThrownBy(() -> service.createTool(req)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createTool_insertsAndReturnsDto() {
        when(toolRepository.existsByName("locust")).thenReturn(false);
        Query q = mockChainedNativeQuery();
        when(entityManager.createNativeQuery(org.mockito.ArgumentMatchers.contains("INSERT INTO load_test_tools")))
                .thenReturn(q);
        when(q.executeUpdate()).thenReturn(1);

        UUID id = UUID.randomUUID();
        LoadTestToolEntity saved = LoadTestToolEntity.builder()
                .id(id)
                .name("LOCUST")
                .dockerImage("locust:latest")
                .fileExtensions(List.of(".py"))
                .enabled(true)
                .createdAt(OffsetDateTime.MIN)
                .updatedAt(OffsetDateTime.MIN)
                .build();
        when(toolRepository.findById(org.mockito.ArgumentMatchers.any(UUID.class))).thenReturn(Optional.of(saved));

        CreateLoadTestToolRequest req = CreateLoadTestToolRequest.builder()
                .name("locust")
                .dockerImage("locust:latest")
                .fileExtensions(List.of(".py"))
                .enabled(true)
                .build();
        assertThat(service.createTool(req).getName()).isEqualTo("LOCUST");
        verify(q).executeUpdate();
    }

    @Test
    void createTool_escapesExtensionsInPgArrayLiteral() {
        when(toolRepository.existsByName("t")).thenReturn(false);
        Query q = mockChainedNativeQuery();
        when(entityManager.createNativeQuery(org.mockito.ArgumentMatchers.contains("INSERT INTO load_test_tools")))
                .thenReturn(q);
        when(q.executeUpdate()).thenReturn(1);
        UUID id = UUID.randomUUID();
        when(toolRepository.findById(org.mockito.ArgumentMatchers.any(UUID.class))).thenReturn(Optional.of(
                LoadTestToolEntity.builder()
                        .id(id)
                        .name("T")
                        .dockerImage("i")
                        .fileExtensions(List.of(".x"))
                        .enabled(true)
                        .createdAt(OffsetDateTime.MIN)
                        .updatedAt(OffsetDateTime.MIN)
                        .build()));

        service.createTool(CreateLoadTestToolRequest.builder()
                .name("t")
                .dockerImage("i")
                .fileExtensions(List.of("a\"b", "c\\d"))
                .enabled(false)
                .build());
        verify(q).setParameter(eq("fileExtensionsStr"), eq("{\"a\\\"b\",\"c\\\\d\"}"));
    }

    @Test
    void createTool_withEmptyExtensionsUsesEmptyPgArray() {
        when(toolRepository.existsByName("k6")).thenReturn(false);
        Query q = mockChainedNativeQuery();
        when(entityManager.createNativeQuery(org.mockito.ArgumentMatchers.contains("INSERT INTO load_test_tools")))
                .thenReturn(q);
        when(q.executeUpdate()).thenReturn(1);
        UUID id = UUID.randomUUID();
        when(toolRepository.findById(org.mockito.ArgumentMatchers.any(UUID.class))).thenReturn(Optional.of(
                LoadTestToolEntity.builder()
                        .id(id)
                        .name("K6")
                        .dockerImage("i")
                        .fileExtensions(List.of())
                        .enabled(true)
                        .createdAt(OffsetDateTime.MIN)
                        .updatedAt(OffsetDateTime.MIN)
                        .build()));

        service.createTool(CreateLoadTestToolRequest.builder()
                .name("k6")
                .dockerImage("i")
                .fileExtensions(List.of())
                .enabled(true)
                .build());
        verify(q).setParameter(eq("fileExtensionsStr"), eq("{}"));
    }

    @Test
    void getAll_getEnabled_getById_getByName() {
        UUID id = UUID.randomUUID();
        LoadTestToolEntity e = LoadTestToolEntity.builder()
                .id(id)
                .name("K6")
                .dockerImage("k6")
                .fileExtensions(List.of(".js"))
                .enabled(true)
                .createdAt(OffsetDateTime.MIN)
                .updatedAt(OffsetDateTime.MIN)
                .build();
        when(toolRepository.findAll()).thenReturn(List.of(e));
        when(toolRepository.findByEnabledTrue()).thenReturn(List.of(e));
        when(toolRepository.findById(id)).thenReturn(Optional.of(e));
        when(toolRepository.findByName("K6")).thenReturn(Optional.of(e));

        assertThat(service.getAllTools()).hasSize(1);
        assertThat(service.getEnabledTools()).hasSize(1);
        assertThat(service.getToolById(id).getDockerImage()).isEqualTo("k6");
        assertThat(service.getToolByName("k6").getId()).isEqualTo(id);
    }

    @Test
    void getToolById_notFound() {
        when(toolRepository.findById(any(UUID.class))).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getToolById(UUID.randomUUID())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getToolByName_notFound() {
        when(toolRepository.findByName("X")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getToolByName("x")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateTool_notFound() {
        when(toolRepository.existsById(any(UUID.class))).thenReturn(false);
        assertThatThrownBy(() -> service.updateTool(UUID.randomUUID(), new UpdateLoadTestToolRequest()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateTool_noFieldChanges_skipsNativeUpdate() {
        UUID id = UUID.randomUUID();
        when(toolRepository.existsById(id)).thenReturn(true);
        LoadTestToolEntity e = LoadTestToolEntity.builder()
                .id(id)
                .name("K6")
                .dockerImage("k6")
                .fileExtensions(List.of(".js"))
                .enabled(true)
                .createdAt(OffsetDateTime.MIN)
                .updatedAt(OffsetDateTime.MIN)
                .build();
        when(toolRepository.findById(id)).thenReturn(Optional.of(e));

        assertThat(service.updateTool(id, new UpdateLoadTestToolRequest()).getId()).isEqualTo(id);
        verify(entityManager, never()).createNativeQuery(org.mockito.ArgumentMatchers.contains("UPDATE load_test_tools"));
    }

    @Test
    void updateTool_runsDynamicUpdate() {
        UUID id = UUID.randomUUID();
        when(toolRepository.existsById(id)).thenReturn(true);
        Query q = mockChainedNativeQuery();
        when(entityManager.createNativeQuery(org.mockito.ArgumentMatchers.contains("UPDATE load_test_tools")))
                .thenReturn(q);
        when(q.executeUpdate()).thenReturn(1);
        LoadTestToolEntity updated = LoadTestToolEntity.builder()
                .id(id)
                .name("K6")
                .dockerImage("newimg")
                .fileExtensions(List.of(".js"))
                .enabled(false)
                .createdAt(OffsetDateTime.MIN)
                .updatedAt(OffsetDateTime.MIN)
                .build();
        when(toolRepository.findById(id)).thenReturn(Optional.of(updated));

        UpdateLoadTestToolRequest req = new UpdateLoadTestToolRequest();
        req.setDockerImage("newimg");
        assertThat(service.updateTool(id, req).getDockerImage()).isEqualTo("newimg");
        verify(q).executeUpdate();
    }

    @Test
    void updateTool_updatesExtensionsAndEnabled() {
        UUID id = UUID.randomUUID();
        when(toolRepository.existsById(id)).thenReturn(true);
        Query q = mockChainedNativeQuery();
        when(entityManager.createNativeQuery(org.mockito.ArgumentMatchers.contains("UPDATE load_test_tools")))
                .thenReturn(q);
        when(q.executeUpdate()).thenReturn(1);
        when(toolRepository.findById(id)).thenReturn(Optional.of(
                LoadTestToolEntity.builder()
                        .id(id)
                        .name("K6")
                        .dockerImage("img")
                        .fileExtensions(List.of(".js"))
                        .enabled(false)
                        .createdAt(OffsetDateTime.MIN)
                        .updatedAt(OffsetDateTime.MIN)
                        .build()));

        UpdateLoadTestToolRequest req = new UpdateLoadTestToolRequest();
        req.setEnabled(false);
        req.setFileExtensions(List.of("a\"b", "c\\d"));
        service.updateTool(id, req);
        verify(q).setParameter(eq("fileExtensions"), eq("{\"a\\\"b\",\"c\\\\d\"}"));
        verify(q).setParameter(eq("enabled"), eq(false));
        verify(q).executeUpdate();
    }

    @Test
    void deleteTool() {
        UUID id = UUID.randomUUID();
        when(toolRepository.existsById(id)).thenReturn(false);
        assertThatThrownBy(() -> service.deleteTool(id)).isInstanceOf(IllegalArgumentException.class);
        when(toolRepository.existsById(id)).thenReturn(true);
        service.deleteTool(id);
        verify(toolRepository).deleteById(id);
    }
}
