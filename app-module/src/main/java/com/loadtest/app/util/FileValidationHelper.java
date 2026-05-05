package com.loadtest.app.util;

import org.springframework.web.multipart.MultipartFile;

public class FileValidationHelper {

    public static String getFileExtension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex == -1 || lastDotIndex == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(lastDotIndex + 1).toLowerCase();
    }

    public static String validateFile(MultipartFile file) {
        if (file.isEmpty()) {
            return "File is empty";
        }
        
        String fileName = file.getOriginalFilename();
        if (fileName == null || fileName.trim().isEmpty()) {
            return "File name is required";
        }
        
        return null;
    }
}


