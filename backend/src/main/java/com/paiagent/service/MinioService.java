package com.paiagent.service;

import com.paiagent.config.MinioConfig;
import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.http.Method;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class MinioService {

    private static final int PRESIGNED_URL_EXPIRY_HOURS = 24;
    
    @Autowired
    private MinioClient minioClient;
    
    @Autowired
    private MinioConfig minioConfig;
    
    /**
     * 确保 bucket 存在
     */
    private void ensureBucketExists() throws Exception {
        boolean exists = minioClient.bucketExists(
                BucketExistsArgs.builder()
                        .bucket(minioConfig.getBucketName())
                        .build()
        );
        
        if (!exists) {
            minioClient.makeBucket(
                    MakeBucketArgs.builder()
                            .bucket(minioConfig.getBucketName())
                            .build()
            );
            log.info("创建 MinIO bucket: {}", minioConfig.getBucketName());
        }
    }
    
    /**
     * 上传文件到 MinIO
     * @param objectName 对象名称 (如: audio/xxx.wav)
     * @param inputStream 文件流
     * @param contentType 文件类型
     * @return 文件的预签名访问 URL
     */
    public String uploadFile(String objectName, InputStream inputStream, String contentType, long size) throws Exception {
        ensureBucketExists();
        
        minioClient.putObject(
                PutObjectArgs.builder()
                        .bucket(minioConfig.getBucketName())
                        .object(objectName)
                        .stream(inputStream, size, -1)
                        .contentType(contentType)
                        .build()
        );
        
        log.info("文件上传成功到 MinIO: {}", objectName);
        
        // 用浏览器可访问的 publicUrl 签名，避免 Docker 内部服务名出现在返回 URL 中。
        MinioClient presignClient = MinioClient.builder()
                .endpoint(minioConfig.getPublicUrl())
                .region(minioConfig.getRegion())
                .credentials(minioConfig.getAccessKey(), minioConfig.getSecretKey())
                .build();
        return presignClient.getPresignedObjectUrl(
                GetPresignedObjectUrlArgs.builder()
                        .method(Method.GET)
                        .bucket(minioConfig.getBucketName())
                        .object(objectName)
                        .expiry(PRESIGNED_URL_EXPIRY_HOURS, TimeUnit.HOURS)
                        .build()
        );
    }
    
    /**
     * 从 URL 下载文件并上传到 MinIO
     * @param fileUrl 文件 URL
     * @param objectName 对象名称
     * @param contentType 文件类型
     * @return MinIO 预签名 URL
     */
    public String uploadFromUrl(String fileUrl, String objectName, String contentType) throws Exception {
        log.info("从 URL 下载文件: {}", fileUrl);
        
        URL url = new URL(fileUrl);
        try (InputStream inputStream = url.openStream()) {
            long size = url.openConnection().getContentLengthLong();
            return uploadFile(objectName, inputStream, contentType, size);
        }
    }
    
    /**
     * 从字节数组上传到 MinIO
     * @param data 字节数组
     * @param objectName 对象名称
     * @param contentType 文件类型
     * @return MinIO 预签名 URL
     */
    public String uploadFromBytes(byte[] data, String objectName, String contentType) throws Exception {
        log.info("从字节数组上传文件到 MinIO: {}, 大小: {} bytes", objectName, data.length);
        
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(data)) {
            return uploadFile(objectName, inputStream, contentType, data.length);
        }
    }
}
