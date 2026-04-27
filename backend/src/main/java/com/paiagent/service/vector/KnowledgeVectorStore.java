package com.paiagent.service.vector;

import com.paiagent.entity.KnowledgeChunk;

import java.util.List;

public interface KnowledgeVectorStore {

    String type();

    void upsert(List<KnowledgeChunk> chunks);

    List<VectorSearchHit> search(Long knowledgeBaseId, List<Double> queryEmbedding, int topK, double minScore);

    void deleteKnowledgeBase(Long knowledgeBaseId);
}
