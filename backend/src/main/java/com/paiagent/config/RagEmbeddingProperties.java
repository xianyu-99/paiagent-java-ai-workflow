package com.paiagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "paiagent.rag.embedding")
public class RagEmbeddingProperties {

    /**
     * dashscope: 阿里云百炼 OpenAI 兼容 Embedding。
     */
    private String provider = "dashscope";

    private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";

    private String apiKey = "";

    private String model = "text-embedding-v4";

    private Integer dimensions = 1024;

    private Integer timeoutMs = 30000;

    private Integer batchSize = 16;

    private Boolean requestBatchingEnabled = true;

    private Integer requestBatchWindowMs = 20;

    private Boolean cacheEnabled = true;

    private Integer cacheTtlSeconds = 3600;

    private Integer cacheMaxSize = 2048;

    private Integer maxConcurrentRequests = 4;

    private Double rateLimitPermitsPerSecond = 4.0;

    private Integer retryMaxAttempts = 3;

    private Integer retryInitialBackoffMs = 300;

    private Integer retryMaxBackoffMs = 2000;
}
