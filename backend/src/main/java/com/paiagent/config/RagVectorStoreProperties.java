package com.paiagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "paiagent.rag.vector-store")
public class RagVectorStoreProperties {

    private String qdrantUrl = "http://localhost:6333";

    private String qdrantApiKey = "";

    private String collectionPrefix = "paiagent_chunks";

    private Integer timeoutMs = 30000;
}
