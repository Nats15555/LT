package com.loadtest.app.service;

import com.loadtest.app.dto.CreateSummarizerRequest;
import com.loadtest.app.dto.UpdateSummarizerRequest;
import com.loadtest.app.persistence.SummarizerModelEntity;
import com.loadtest.app.persistence.SummarizerModelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SummarizerServiceTest {

    @Mock
    private SummarizerModelRepository repository;

    private SummarizerService service;

    @BeforeEach
    void setUp() {
        service = new SummarizerService(repository);
    }

    @Test
    void create_openAi_requiresModelId() {
        when(repository.existsByName("m")).thenReturn(false);
        assertThatThrownBy(() -> service.create(new CreateSummarizerRequest("m", "OPENAI", null, "  ", null, true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Model ID");
    }

    @Test
    void create_external_requiresHttpBaseUrl() {
        when(repository.existsByName("e")).thenReturn(false);
        assertThatThrownBy(() -> service.create(new CreateSummarizerRequest("e", "EXTERNAL", "ftp://x", null, null, true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("http");
    }

    @Test
    void create_external_defaultsModelId() {
        when(repository.existsByName("e")).thenReturn(false);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service.create(new CreateSummarizerRequest("e", "EXTERNAL", "https://ingest.example/hook", null, null, true));
        ArgumentCaptor<SummarizerModelEntity> cap = ArgumentCaptor.forClass(SummarizerModelEntity.class);
        verify(repository).save(cap.capture());
        assertThat(cap.getValue().getModelId()).isEqualTo("external");
    }

    @Test
    void create_duplicateName() {
        when(repository.existsByName("dup")).thenReturn(true);
        assertThatThrownBy(() -> service.create(new CreateSummarizerRequest("dup", null, null, "gpt", null, true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void create_defaultsProviderToOpenAiWhenNull() {
        when(repository.existsByName("n")).thenReturn(false);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        var dto = service.create(new CreateSummarizerRequest("n", null, null, "gpt-4o-mini", null, true));
        assertThat(dto.provider()).isEqualTo("OPENAI");
    }

    @Test
    void getAll_getEnabled_getById_getByName() {
        UUID id = UUID.randomUUID();
        SummarizerModelEntity e = SummarizerModelEntity.builder()
                .id(id)
                .name("n")
                .provider("OPENAI")
                .modelId("m")
                .enabled(true)
                .createdAt(OffsetDateTime.MIN)
                .updatedAt(OffsetDateTime.MIN)
                .build();
        when(repository.findAll()).thenReturn(List.of(e));
        when(repository.findByEnabledTrue()).thenReturn(List.of(e));
        when(repository.findById(id)).thenReturn(Optional.of(e));
        when(repository.findByName("n")).thenReturn(Optional.of(e));

        assertThat(service.getAll()).hasSize(1);
        assertThat(service.getEnabled()).hasSize(1);
        assertThat(service.getById(id).name()).isEqualTo("n");
        assertThat(service.getByName("n").id()).isEqualTo(id);
    }

    @Test
    void getById_notFound() {
        when(repository.findById(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getById(UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getByName_notFound() {
        when(repository.findByName("x")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getByName("x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void update_patchesFieldsAndValidates() {
        UUID id = UUID.randomUUID();
        SummarizerModelEntity e = SummarizerModelEntity.builder()
                .id(id)
                .name("n")
                .provider("OPENAI")
                .modelId("mid")
                .enabled(true)
                .createdAt(OffsetDateTime.MIN)
                .updatedAt(OffsetDateTime.MIN)
                .build();
        when(repository.findById(id)).thenReturn(Optional.of(e));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateSummarizerRequest req = new UpdateSummarizerRequest(null, null, null, null, false);
        assertThat(service.update(id, req).enabled()).isFalse();
    }

    @Test
    void update_externalInvalidBaseUrl() {
        UUID id = UUID.randomUUID();
        SummarizerModelEntity e = SummarizerModelEntity.builder()
                .id(id)
                .name("n")
                .provider("OPENAI")
                .modelId("mid")
                .enabled(true)
                .createdAt(OffsetDateTime.MIN)
                .updatedAt(OffsetDateTime.MIN)
                .build();
        when(repository.findById(id)).thenReturn(Optional.of(e));
        UpdateSummarizerRequest req = new UpdateSummarizerRequest("EXTERNAL", "noscheme", null, null, null);
        assertThatThrownBy(() -> service.update(id, req)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void update_openAiBlankModelId_throws() {
        UUID id = UUID.randomUUID();
        SummarizerModelEntity e = SummarizerModelEntity.builder()
                .id(id)
                .name("n")
                .provider("OPENAI")
                .modelId("mid")
                .enabled(true)
                .createdAt(OffsetDateTime.MIN)
                .updatedAt(OffsetDateTime.MIN)
                .build();
        when(repository.findById(id)).thenReturn(Optional.of(e));
        UpdateSummarizerRequest req = new UpdateSummarizerRequest(null, null, " ", null, null);
        assertThatThrownBy(() -> service.update(id, req)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Model ID");
    }

    @Test
    void update_externalBlankModelId_becomesExternal() {
        UUID id = UUID.randomUUID();
        SummarizerModelEntity e = SummarizerModelEntity.builder()
                .id(id)
                .name("n")
                .provider("OPENAI")
                .modelId("mid")
                .enabled(true)
                .createdAt(OffsetDateTime.MIN)
                .updatedAt(OffsetDateTime.MIN)
                .build();
        when(repository.findById(id)).thenReturn(Optional.of(e));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateSummarizerRequest req = new UpdateSummarizerRequest("EXTERNAL", "https://ingest.local/cb", " ", null, null);
        var dto = service.update(id, req);
        assertThat(dto.provider()).isEqualTo("EXTERNAL");
        assertThat(dto.modelId()).isEqualTo("external");
    }

    @Test
    void delete_okAndNotFound() {
        UUID id = UUID.randomUUID();
        when(repository.existsById(id)).thenReturn(true);
        service.delete(id);
        verify(repository).deleteById(id);

        when(repository.existsById(id)).thenReturn(false);
        assertThatThrownBy(() -> service.delete(id)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void privateHelpers_coverProviderAndValidationBranches() {
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "normalizeProvider", new Object[]{null})).isEqualTo("OPENAI");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "normalizeProvider", "   ")).isEqualTo("OPENAI");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "normalizeProvider", "external")).isEqualTo("EXTERNAL");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "normalizeProvider", "azure")).isEqualTo("OPENAI");

        assertThat((String) ReflectionTestUtils.invokeMethod(service, "trimToEmpty", new Object[]{null})).isEmpty();
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "trimToEmpty", "  x ")).isEqualTo("x");

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "validateExternalIngestUrl", " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "validateExternalIngestUrl", "ftp://x"))
                .isInstanceOf(IllegalArgumentException.class);
        ReflectionTestUtils.invokeMethod(service, "validateExternalIngestUrl", "https://ok");
    }

    @Test
    void validatePersistedSummarizer_branches() {
        SummarizerModelEntity ext = SummarizerModelEntity.builder()
                .name("e").provider("EXTERNAL").baseUrl("https://ingest").modelId(" ").enabled(true)
                .createdAt(OffsetDateTime.MIN).updatedAt(OffsetDateTime.MIN).build();
        ReflectionTestUtils.invokeMethod(service, "validatePersistedSummarizer", ext);
        assertThat(ext.getModelId()).isEqualTo("external");

        SummarizerModelEntity open = SummarizerModelEntity.builder()
                .name("o").provider("OPENAI").modelId(" ").enabled(true)
                .createdAt(OffsetDateTime.MIN).updatedAt(OffsetDateTime.MIN).build();
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "validatePersistedSummarizer", open))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
