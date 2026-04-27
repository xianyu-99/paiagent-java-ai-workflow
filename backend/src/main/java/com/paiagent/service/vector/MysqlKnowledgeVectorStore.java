package com.paiagent.service.vector;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.paiagent.entity.KnowledgeChunk;
import com.paiagent.mapper.KnowledgeChunkMapper;
import com.paiagent.service.TextEmbeddingService;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

@Component
public class MysqlKnowledgeVectorStore implements KnowledgeVectorStore {

    private final KnowledgeChunkMapper knowledgeChunkMapper;

    private final TextEmbeddingService textEmbeddingService;

    public MysqlKnowledgeVectorStore(KnowledgeChunkMapper knowledgeChunkMapper,
                                     TextEmbeddingService textEmbeddingService) {
        this.knowledgeChunkMapper = knowledgeChunkMapper;
        this.textEmbeddingService = textEmbeddingService;
    }

    @Override
    public String type() {
        return "mysql";
    }

    @Override
    public void upsert(List<KnowledgeChunk> chunks) {
        // MySQL 是知识切片的主存储，向量已随 chunk 一起写入，无需额外索引动作。
    }

    @Override
    public List<VectorSearchHit> search(Long knowledgeBaseId, List<Double> queryEmbedding, int topK, double minScore) {
        return knowledgeChunkMapper.selectList(new LambdaQueryWrapper<KnowledgeChunk>()
                        .eq(KnowledgeChunk::getKnowledgeBaseId, knowledgeBaseId))
                .stream()
                .filter(this::isCompatibleEmbedding)
                .map(chunk -> new VectorSearchHit(
                        chunk.getId(),
                        textEmbeddingService.cosine(queryEmbedding, textEmbeddingService.deserialize(chunk.getEmbedding()))
                ))
                .filter(hit -> hit.score() >= minScore)
                .sorted(Comparator.comparing(VectorSearchHit::score).reversed())
                .limit(Math.max(1, topK))
                .toList();
    }

    @Override
    public void deleteKnowledgeBase(Long knowledgeBaseId) {
        // MySQL 删除由 KnowledgeBaseService 控制，这里不重复处理。
    }

    private boolean isCompatibleEmbedding(KnowledgeChunk chunk) {
        return textEmbeddingService.isCompatible(
                chunk.getEmbeddingProvider(),
                chunk.getEmbeddingModel(),
                chunk.getEmbeddingDimension()
        );
    }
}
