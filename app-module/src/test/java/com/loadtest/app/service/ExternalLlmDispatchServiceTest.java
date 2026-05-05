package com.loadtest.app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadtest.app.persistence.SummarizerModelEntity;
import com.loadtest.app.persistence.SummarizerModelRepository;
import com.loadtest.app.persistence.TestTaskHistoryEntity;
import com.loadtest.app.persistence.TestTaskHistoryRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExternalLlmDispatchServiceTest {

    @Mock
    private ExternalSummarizationCallbackService callbackService;
    @Mock
    private SummarizerModelRepository summarizerModelRepository;
    @Mock
    private TestTaskHistoryRepository historyRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ExternalLlmDispatchService service;
    private HttpServer server;

    @BeforeEach
    void setUp() {
        service = new ExternalLlmDispatchService(callbackService, summarizerModelRepository, historyRepository, objectMapper);
        ReflectionTestUtils.setField(service, "fallbackReceiverUrl", "");
        ReflectionTestUtils.setField(service, "rewriteDockerServiceHostTo", "");
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void startServer(int status, String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ack", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, b.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(b);
            }
        });
        server.start();
    }

    @Test
    void dispatch_success() throws Exception {
        startServer(200, "{\"received\":true}");
        int port = server.getAddress().getPort();
        UUID taskId = UUID.randomUUID();
        OffsetDateTime t = OffsetDateTime.now(java.time.ZoneOffset.UTC);
        when(callbackService.buildPackage(taskId)).thenReturn(Map.of("taskId", taskId.toString()));
        when(historyRepository.findById(taskId)).thenReturn(Optional.of(
                TestTaskHistoryEntity.builder()
                        .id(taskId).finalStatus("OK").createdAt(t).movedAt(t).testTool("k6").testFileName("f.js")
                        .testFileContentBase64("QQ==").command("c").summarizerName("ext").build()));
        when(summarizerModelRepository.findByName("ext")).thenReturn(Optional.of(
                SummarizerModelEntity.builder()
                        .id(UUID.randomUUID()).name("ext").provider("EXTERNAL").modelId("m").baseUrl("http://127.0.0.1:" + port + "/ack")
                        .enabled(true).createdAt(t).updatedAt(t).build()));

        Map<String, Object> res = service.dispatchPackage(taskId);
        assertThat(res.get("received")).isEqualTo(true);
    }

    @Test
    void dispatch_connectFailure_invokesFailPending() {
        UUID taskId = UUID.randomUUID();
        OffsetDateTime t = OffsetDateTime.now(java.time.ZoneOffset.UTC);
        when(callbackService.buildPackage(taskId)).thenReturn(Map.of("k", "v"));
        when(historyRepository.findById(taskId)).thenReturn(Optional.of(
                TestTaskHistoryEntity.builder()
                        .id(taskId).finalStatus("OK").createdAt(t).movedAt(t).testTool("k6").testFileName("f.js")
                        .testFileContentBase64("QQ==").command("c").summarizerName("ext").build()));
        when(summarizerModelRepository.findByName("ext")).thenReturn(Optional.of(
                SummarizerModelEntity.builder()
                        .id(UUID.randomUUID()).name("ext").provider("EXTERNAL").modelId("m").baseUrl("http://127.0.0.1:1/closed")
                        .enabled(true).createdAt(t).updatedAt(t).build()));
        assertThatThrownBy(() -> service.dispatchPackage(taskId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(HttpStatus.BAD_GATEWAY.value()));
        verify(callbackService).failPendingWindow(eq(taskId), org.mockito.ArgumentMatchers.contains("Не удалось"));
    }

    @Test
    void dispatch_receivedFalse_invokesFailPending() throws Exception {
        startServer(200, "{\"received\":false,\"reason\":\"nope\"}");
        int port = server.getAddress().getPort();
        UUID taskId = UUID.randomUUID();
        OffsetDateTime t = OffsetDateTime.now(java.time.ZoneOffset.UTC);
        when(callbackService.buildPackage(taskId)).thenReturn(Map.of("k", "v"));
        when(historyRepository.findById(taskId)).thenReturn(Optional.of(
                TestTaskHistoryEntity.builder()
                        .id(taskId).finalStatus("OK").createdAt(t).movedAt(t).testTool("k6").testFileName("f.js")
                        .testFileContentBase64("QQ==").command("c").summarizerName("ext").build()));
        when(summarizerModelRepository.findByName("ext")).thenReturn(Optional.of(
                SummarizerModelEntity.builder()
                        .id(UUID.randomUUID()).name("ext").provider("EXTERNAL").modelId("m").baseUrl("http://127.0.0.1:" + port + "/ack")
                        .enabled(true).createdAt(t).updatedAt(t).build()));
        assertThatThrownBy(() -> service.dispatchPackage(taskId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(HttpStatus.BAD_GATEWAY.value()));
        verify(callbackService).failPendingWindow(eq(taskId), org.mockito.ArgumentMatchers.contains("не принял"));
    }

    @Test
    void dispatch_receivedFalse_withoutReason_usesBaseMessage() throws Exception {
        startServer(200, "{\"received\":false}");
        int port = server.getAddress().getPort();
        UUID taskId = UUID.randomUUID();
        OffsetDateTime t = OffsetDateTime.now(java.time.ZoneOffset.UTC);
        when(callbackService.buildPackage(taskId)).thenReturn(Map.of("k", "v"));
        when(historyRepository.findById(taskId)).thenReturn(Optional.of(
                TestTaskHistoryEntity.builder()
                        .id(taskId).finalStatus("OK").createdAt(t).movedAt(t).testTool("k6").testFileName("f.js")
                        .testFileContentBase64("QQ==").command("c").summarizerName("ext").build()));
        when(summarizerModelRepository.findByName("ext")).thenReturn(Optional.of(
                SummarizerModelEntity.builder()
                        .id(UUID.randomUUID()).name("ext").provider("EXTERNAL").modelId("m").baseUrl("http://127.0.0.1:" + port + "/ack")
                        .enabled(true).createdAt(t).updatedAt(t).build()));

        assertThatThrownBy(() -> service.dispatchPackage(taskId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY));
        verify(callbackService).failPendingWindow(eq(taskId), eq("Внешний контур не принял пакет"));
    }

    @Test
    void dispatch_validationAndRewrite() throws Exception {
        UUID taskId = UUID.randomUUID();
        OffsetDateTime t = OffsetDateTime.now(java.time.ZoneOffset.UTC);
        when(callbackService.buildPackage(taskId)).thenReturn(Map.of());
        when(historyRepository.findById(taskId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.dispatchPackage(taskId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(HttpStatus.NOT_FOUND.value()));

        when(historyRepository.findById(taskId)).thenReturn(Optional.of(
                TestTaskHistoryEntity.builder()
                        .id(taskId).finalStatus("OK").createdAt(t).movedAt(t).testTool("k6").testFileName("f.js")
                        .testFileContentBase64("QQ==").command("c").summarizerName(null).build()));
        assertThatThrownBy(() -> service.dispatchPackage(taskId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(HttpStatus.BAD_REQUEST.value()));

        when(historyRepository.findById(taskId)).thenReturn(Optional.of(
                TestTaskHistoryEntity.builder()
                        .id(taskId).finalStatus("OK").createdAt(t).movedAt(t).testTool("k6").testFileName("f.js")
                        .testFileContentBase64("QQ==").command("c").summarizerName("r").build()));
        when(summarizerModelRepository.findByName("r")).thenReturn(Optional.of(
                SummarizerModelEntity.builder()
                        .id(UUID.randomUUID()).name("r").provider("OPENAI").modelId("m").enabled(true).createdAt(t).updatedAt(t).build()));
        assertThatThrownBy(() -> service.dispatchPackage(taskId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(HttpStatus.BAD_REQUEST.value()));

        when(summarizerModelRepository.findByName("r")).thenReturn(Optional.of(
                SummarizerModelEntity.builder()
                        .id(UUID.randomUUID()).name("r").provider("EXTERNAL").modelId("m").baseUrl("  ")
                        .enabled(true).createdAt(t).updatedAt(t).build()));
        ReflectionTestUtils.setField(service, "fallbackReceiverUrl", "   ");
        assertThatThrownBy(() -> service.dispatchPackage(taskId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(HttpStatus.BAD_GATEWAY.value()));

        startServer(200, "{\"received\":true}");
        int port = server.getAddress().getPort();
        ReflectionTestUtils.setField(service, "fallbackReceiverUrl", "");
        ReflectionTestUtils.setField(service, "rewriteDockerServiceHostTo", "127.0.0.1");
        when(summarizerModelRepository.findByName("r")).thenReturn(Optional.of(
                SummarizerModelEntity.builder()
                        .id(UUID.randomUUID()).name("r").provider("EXTERNAL").modelId("m")
                        .baseUrl("http://external-llm-mock:" + port + "/ack")
                        .enabled(true).createdAt(t).updatedAt(t).build()));
        assertThat(service.dispatchPackage(taskId).get("status")).isEqualTo("success");
    }

    @Test
    void dispatch_httpError_andBlankBody_andFallbackUrl() throws Exception {
        UUID taskId = UUID.randomUUID();
        OffsetDateTime t = OffsetDateTime.now(java.time.ZoneOffset.UTC);
        when(callbackService.buildPackage(taskId)).thenReturn(Map.of("taskId", taskId.toString()));
        when(historyRepository.findById(taskId)).thenReturn(Optional.of(
                TestTaskHistoryEntity.builder()
                        .id(taskId).finalStatus("OK").createdAt(t).movedAt(t).testTool("k6").testFileName("f.js")
                        .testFileContentBase64("QQ==").command("c").summarizerName("ext").build()));

        startServer(502, "");
        int port = server.getAddress().getPort();
        when(summarizerModelRepository.findByName("ext")).thenReturn(Optional.of(
                SummarizerModelEntity.builder()
                        .id(UUID.randomUUID()).name("ext").provider("EXTERNAL").modelId("m").baseUrl("http://127.0.0.1:" + port + "/ack")
                        .enabled(true).createdAt(t).updatedAt(t).build()));
        assertThatThrownBy(() -> service.dispatchPackage(taskId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY));
        verify(callbackService).failPendingWindow(eq(taskId), org.mockito.ArgumentMatchers.contains("HTTP 502"));

        if (server != null) {
            server.stop(0);
            server = null;
        }
        startServer(200, "");
        int okPort = server.getAddress().getPort();
        ReflectionTestUtils.setField(service, "fallbackReceiverUrl", "http://127.0.0.1:" + okPort + "/ack");
        when(summarizerModelRepository.findByName("ext")).thenReturn(Optional.of(
                SummarizerModelEntity.builder()
                        .id(UUID.randomUUID()).name("ext").provider("EXTERNAL").modelId("m").baseUrl("   ")
                        .enabled(true).createdAt(t).updatedAt(t).build()));
        assertThatThrownBy(() -> service.dispatchPackage(taskId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY));

        if (server != null) {
            server.stop(0);
            server = null;
        }
        startServer(200, "{\"received\":true}");
        int fallbackOkPort = server.getAddress().getPort();
        ReflectionTestUtils.setField(service, "fallbackReceiverUrl", "http://127.0.0.1:" + fallbackOkPort + "/ack");
        assertThat(service.dispatchPackage(taskId).get("status")).isEqualTo("success");
    }

    @Test
    void rewriteAndDescribeHelpers_coverRemainingBranches() {
        ReflectionTestUtils.setField(service, "rewriteDockerServiceHostTo", "127.0.0.1");
        String untouched = ReflectionTestUtils.invokeMethod(service, "rewriteDockerServiceHostname", "http://example.org/ack");
        assertThat(untouched).isEqualTo("http://example.org/ack");
        String nullUrl = ReflectionTestUtils.invokeMethod(service, "rewriteDockerServiceHostname", new Object[]{null});
        assertThat(nullUrl).isNull();
        String rewritten = ReflectionTestUtils.invokeMethod(service, "rewriteDockerServiceHostname", "http://external-llm-mock:8080/ack");
        assertThat(rewritten).contains("127.0.0.1");

        String nullDescription = ReflectionTestUtils.invokeMethod(service, "describeExceptionChain", new Object[]{null});
        assertThat(nullDescription).isEqualTo("(null)");

        RuntimeException chain = new RuntimeException("top", new IllegalStateException("mid", new IllegalArgumentException("root")));
        String described = ReflectionTestUtils.invokeMethod(service, "describeExceptionChain", chain);
        assertThat(described).contains("RuntimeException: top");
        assertThat(described).contains("IllegalStateException: mid");
        assertThat(described).contains("IllegalArgumentException: root");
        String blankMsg = ReflectionTestUtils.invokeMethod(service, "describeExceptionChain", new RuntimeException(" "));
        assertThat(blankMsg).isEqualTo("RuntimeException");

        String a = ReflectionTestUtils.invokeMethod(service, "firstNonBlank", "  http://x  ", "http://y");
        String b = ReflectionTestUtils.invokeMethod(service, "firstNonBlank", "   ", "  http://y  ");
        String c = ReflectionTestUtils.invokeMethod(service, "firstNonBlank", "   ", "   ");
        assertThat(a).isEqualTo("http://x");
        assertThat(b).isEqualTo("http://y");
        assertThat(c).isNull();
    }

    @Test
    void rewriteHelper_whenRewriteTargetBlank_returnsOriginal() {
        ReflectionTestUtils.setField(service, "rewriteDockerServiceHostTo", "   ");
        String same = ReflectionTestUtils.invokeMethod(
                service, "rewriteDockerServiceHostname", "http://external-llm-mock:8095/ack");
        assertThat(same).isEqualTo("http://external-llm-mock:8095/ack");
    }
}
