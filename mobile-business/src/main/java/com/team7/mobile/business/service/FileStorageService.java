package com.team7.mobile.business.service;

import com.team7.mobile.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * File storage — saves receipt images (and other uploads) to disk,
 * returns a URL path that the app / web admin can use to fetch the file.
 * Files land under <upload-dir>/receipts/YYYY-MM-DD/<uuid>.<ext>.
 */
@Service
public class FileStorageService {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp", "gif");
    private static final long MAX_SIZE_BYTES = 10L * 1024 * 1024;  // 10MB

    private final Path uploadRoot;

    public FileStorageService(@Value("${app.file.upload-dir}") String uploadDir) {
        this.uploadRoot = Paths.get(uploadDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(uploadRoot);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create upload dir: " + uploadRoot, e);
        }
    }

    /**
     * Store a receipt image and return its URL path (e.g. /uploads/receipts/2026-08-05/abc.jpg).
     */
    public String storeReceipt(MultipartFile file) {
        return store(file, "receipts");
    }

    private String store(MultipartFile file, String category) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("EMPTY_FILE", "No file provided", 400);
        }
        if (file.getSize() > MAX_SIZE_BYTES) {
            throw new BusinessException("FILE_TOO_LARGE", "File exceeds 10MB limit", 400);
        }
        String ext = extensionOf(file.getOriginalFilename());
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new BusinessException("UNSUPPORTED_TYPE", "Only jpg/jpeg/png/webp/gif allowed", 400);
        }

        String dateDir = LocalDate.now().toString();
        String filename = UUID.randomUUID() + "." + ext;
        Path dir = uploadRoot.resolve(category).resolve(dateDir);
        Path target = dir.resolve(filename);
        try {
            Files.createDirectories(dir);
            file.transferTo(target);
        } catch (IOException e) {
            throw new BusinessException("FILE_SAVE_FAILED", "Could not save file", 500);
        }
        return "/uploads/" + category + "/" + dateDir + "/" + filename;
    }

    private String extensionOf(String name) {
        if (name == null || !name.contains(".")) return "";
        return name.substring(name.lastIndexOf('.') + 1).toLowerCase();
    }
}
