package com.zwiki.service;

import com.zwiki.common.exception.BusinessException;
import com.zwiki.config.MinioProperties;
import io.minio.PutObjectArgs;
import io.minio.errors.ErrorResponseException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MinioService {

    private static final String IMAGE_PKG = "images";

    private final ObjectProvider<io.minio.MinioClient> minioClientProvider;

    private final MinioProperties properties;

    public boolean isEnabled() {
        return minioClientProvider.getIfAvailable() != null
                && properties.getUrl() != null && !properties.getUrl().isBlank()
                && properties.getBucketName() != null && !properties.getBucketName().isBlank();
    }

    public String uploadBytes(String objectPath, byte[] bytes, String contentType, String extension) {
        Assert.isTrue(bytes != null && bytes.length > 0, "bytes must not be empty");
        if (!isEnabled()) {
            throw new BusinessException("MinIO未配置，无法上传文件");
        }

        String datePath = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String ext = (extension == null || extension.isBlank()) ? "png" : extension.trim().toLowerCase(Locale.ROOT);
        String fileName = UUID.randomUUID() + "." + ext;

        String normalizedPath = normalizeObjectPath(objectPath);
        String objectName = IMAGE_PKG + "/" + normalizedPath + "/" + datePath + "/" + fileName;

        try {
            String ct = (contentType == null || contentType.isBlank()) ? "application/octet-stream" : contentType;
            io.minio.MinioClient minioClient = minioClientProvider.getIfAvailable();
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(properties.getBucketName())
                    .object(objectName)
                    .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
                    .contentType(ct)
                    .build());
            return buildUrl(objectName);
        } catch (ErrorResponseException e) {
            throw new BusinessException("上传失败: " + safeMessage(e));
        } catch (Exception e) {
            throw new BusinessException("上传失败: " + safeMessage(e));
        }
    }

    public String uploadDocument(String objectPath, byte[] bytes, String filename, String contentType) {
        Assert.isTrue(bytes != null && bytes.length > 0, "bytes must not be empty");
        Assert.hasText(filename, "filename must not be empty");
        if (!isEnabled()) {
            throw new BusinessException("MinIO未配置，无法上传文档");
        }

        String datePath = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String normalizedPath = normalizeObjectPath(objectPath);
        String objectName = "documents/" + normalizedPath + "/" + datePath + "/" + filename;

        try {
            String ct = (contentType == null || contentType.isBlank()) ? "application/octet-stream" : contentType;
            io.minio.MinioClient minioClient = minioClientProvider.getIfAvailable();
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(properties.getBucketName())
                    .object(objectName)
                    .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
                    .contentType(ct)
                    .build());
            return buildUrl(objectName);
        } catch (ErrorResponseException e) {
            throw new BusinessException("文档上传失败: " + safeMessage(e));
        } catch (Exception e) {
            throw new BusinessException("文档上传失败: " + safeMessage(e));
        }
    }

    private String buildUrl(String objectName) {
        String url = properties.getUrl();
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url + "/" + properties.getBucketName() + "/" + objectName;
    }

    private String normalizeObjectPath(String objectPath) {
        if (objectPath == null) {
            return "";
        }
        String trimmed = objectPath.trim();
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private String safeMessage(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
