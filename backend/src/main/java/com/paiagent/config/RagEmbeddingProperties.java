package com.paiagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "paiagent.rag.embedding")
public class RagEmbeddingProperties {

    /**
     * local: 本地 Hash Embedding；dashscope: 阿里云百炼 OpenAI 兼容 Embedding。
     */
    private String provider = "local";

    private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";

    private String apiKey = "";

    private String model = "text-embedding-v4";

    private Integer dimensions = 1024;

    private Integer timeoutMs = 30000;

    private Integer batchSize = 16;
}
