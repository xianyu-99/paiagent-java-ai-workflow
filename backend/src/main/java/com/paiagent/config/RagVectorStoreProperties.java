package com.paiagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "paiagent.rag.vector-store")
public class RagVectorStoreProperties {

    /**
     * mysql: MySQL JSON 向量兜底；qdrant: Qdrant 专用向量库。
     */
    private String provider = "mysql";

    private String qdrantUrl = "http://localhost:6333";

    private String qdrantApiKey = "";

    private String collectionPrefix = "paiagent_chunks";

    private Integer timeoutMs = 30000;

    /**
     * Qdrant 不可用时是否回退到 MySQL 检索，方便本地复现。
     */
    private Boolean fallbackToMysql = true;
}
