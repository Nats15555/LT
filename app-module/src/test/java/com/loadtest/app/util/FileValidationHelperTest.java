package com.loadtest.app.util;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FileValidationHelperTest {

    @Test
    void getFileExtension_handlesNullAndNoExtension() {
        assertThat(FileValidationHelper.getFileExtension(null)).isEmpty();
        assertThat(FileValidationHelper.getFileExtension("")).isEmpty();
        assertThat(FileValidationHelper.getFileExtension("noext")).isEmpty();
        assertThat(FileValidationHelper.getFileExtension("file.")).isEmpty();
    }

    @Test
    void getFileExtension_returnsLowercaseExtension() {
        assertThat(FileValidationHelper.getFileExtension("script.JS")).isEqualTo("js");
        assertThat(FileValidationHelper.getFileExtension("a.b.tar.gz")).isEqualTo("gz");
    }

    @Test
    void validateFile_rejectsEmptyOrMissingName() {
        assertThat(FileValidationHelper.validateFile(new MockMultipartFile("f", new byte[0])))
                .isEqualTo("File is empty");
        assertThat(FileValidationHelper.validateFile(new MockMultipartFile("f", null, "text/plain", new byte[]{1})))
                .isEqualTo("File name is required");
        assertThat(FileValidationHelper.validateFile(new MockMultipartFile("f", "", "text/plain", new byte[]{1})))
                .isEqualTo("File name is required");
        assertThat(FileValidationHelper.validateFile(new MockMultipartFile("f", "   ", "text/plain", new byte[]{1})))
                .isEqualTo("File name is required");
    }

    @Test
    void validateFile_acceptsNonEmptyWithName() {
        assertThat(FileValidationHelper.validateFile(
                new MockMultipartFile("f", "k6.js", "text/plain", new byte[]{1}))).isNull();
    }

    @Test
    void validateFile_rejectsNullFileNameBranch() {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getOriginalFilename()).thenReturn(null);
        assertThat(FileValidationHelper.validateFile(file)).isEqualTo("File name is required");
    }
}
