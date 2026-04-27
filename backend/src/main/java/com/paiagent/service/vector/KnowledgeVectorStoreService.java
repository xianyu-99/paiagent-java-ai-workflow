package com.paiagent.service.vector;

import com.paiagent.config.RagVectorStoreProperties;
import com.paiagent.entity.KnowledgeChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

@Slf4j
@Service
public class KnowledgeVectorStoreService {

    private final RagVectorStoreProperties properties;

    private final MysqlKnowledgeVectorStore mysqlStore;

    private final QdrantKnowledgeVectorStore qdrantStore;

    public KnowledgeVectorStoreService(RagVectorStoreProperties properties,
                                       MysqlKnowledgeVectorStore mysqlStore,
                                       QdrantKnowledgeVectorStore qdrantStore) {
        this.properties = properties;
        this.mysqlStore = mysqlStore;
        this.qdrantStore = qdrantStore;
    }

    public String activeType() {
        return activeStore().type();
    }

    public void upsert(List<KnowledgeChunk> chunks) {
        try {
            activeStore().upsert(chunks);
        } catch (RuntimeException e) {
            if (!shouldFallback()) {
                throw e;
            }
            log.warn("VectorStore upsert failed, fallback to MySQL only: {}", e.getMessage());
            mysqlStore.upsert(chunks);
        }
    }

    public List<VectorSearchHit> search(Long knowledgeBaseId, List<Double> queryEmbedding, int topK, double minScore) {
        try {
            return activeStore().search(knowledgeBaseId, queryEmbedding, topK, minScore);
        } catch (RuntimeException e) {
            if (!shouldFallback()) {
                throw e;
            }
            log.warn("VectorStore search failed, fallback to MySQL: {}", e.getMessage());
            return mysqlStore.search(knowledgeBaseId, queryEmbedding, topK, minScore);
        }
    }

    public void deleteKnowledgeBase(Long knowledgeBaseId) {
        try {
            activeStore().deleteKnowledgeBase(knowledgeBaseId);
        } catch (RuntimeException e) {
            if (!shouldFallback()) {
                throw e;
            }
            log.warn("VectorStore delete failed, ignore because MySQL remains source of truth: {}", e.getMessage());
        }
    }

    private KnowledgeVectorStore activeStore() {
        String provider = properties.getProvider() == null ? "mysql" : properties.getProvider().toLowerCase(Locale.ROOT);
        return switch (provider) {
            case "qdrant" -> qdrantStore;
            case "mysql", "local" -> mysqlStore;
            default -> throw new IllegalArgumentException("Unsupported RAG vector store provider: " + provider);
        };
    }

    private boolean shouldFallback() {
        return Boolean.TRUE.equals(properties.getFallbackToMysql());
    }
}
