package com.loadtest.app.testsupport;

import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import static org.mockito.Mockito.when;

public final class MultipartFileTestSupport {

    private MultipartFileTestSupport() {
    }

    public static void stubBytes(MultipartFile file, byte[] bytes) {
        try {
            when(file.getBytes()).thenReturn(bytes);
        } catch (IOException ex) {
            throw new AssertionError("Unexpected IOException from mocked MultipartFile#getBytes", ex);
        }
    }

    public static void stubIoFailure(MultipartFile file, IOException failure) {
        try {
            when(file.getBytes()).thenThrow(failure);
        } catch (IOException ex) {
            throw new AssertionError("Unexpected IOException from mocked MultipartFile#getBytes", ex);
        }
    }

    public static byte[] gzipUtf8(String text) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             GZIPOutputStream gos = new GZIPOutputStream(bos)) {
            gos.write(text.getBytes(StandardCharsets.UTF_8));
            gos.finish();
            return bos.toByteArray();
        } catch (IOException ex) {
            throw new AssertionError("Failed to build gzip test payload", ex);
        }
    }
}
