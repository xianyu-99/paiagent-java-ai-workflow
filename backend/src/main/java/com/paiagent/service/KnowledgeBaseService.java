package com.paiagent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.paiagent.common.ForbiddenException;
import com.paiagent.dto.KnowledgeBaseRequest;
import com.paiagent.dto.KnowledgeBaseResponse;
import com.paiagent.dto.KnowledgeDocumentResponse;
import com.paiagent.dto.KnowledgeReindexResponse;
import com.paiagent.dto.RetrievedChunk;
import com.paiagent.entity.KnowledgeBase;
import com.paiagent.entity.KnowledgeChunk;
import com.paiagent.entity.KnowledgeDocument;
import com.paiagent.mapper.KnowledgeBaseMapper;
import com.paiagent.mapper.KnowledgeChunkMapper;
import com.paiagent.mapper.KnowledgeDocumentMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class KnowledgeBaseService {

    private static final int MAX_CHUNK_LENGTH = 700;

    private static final int CHUNK_OVERLAP = 80;

    private final KnowledgeBaseMapper knowledgeBaseMapper;

    private final KnowledgeDocumentMapper knowledgeDocumentMapper;

    private final KnowledgeChunkMapper knowledgeChunkMapper;

    private final TextEmbeddingService textEmbeddingService;

    public KnowledgeBaseService(KnowledgeBaseMapper knowledgeBaseMapper,
                                KnowledgeDocumentMapper knowledgeDocumentMapper,
                                KnowledgeChunkMapper knowledgeChunkMapper,
                                TextEmbeddingService textEmbeddingService) {
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.knowledgeDocumentMapper = knowledgeDocumentMapper;
        this.knowledgeChunkMapper = knowledgeChunkMapper;
        this.textEmbeddingService = textEmbeddingService;
    }

    public List<KnowledgeBaseResponse> listKnowledgeBases(Long userId, boolean admin) {
        LambdaQueryWrapper<KnowledgeBase> wrapper = new LambdaQueryWrapper<KnowledgeBase>()
                .orderByDesc(KnowledgeBase::getUpdatedAt);
        if (!admin) {
            wrapper.eq(KnowledgeBase::getOwnerId, userId);
        }
        return knowledgeBaseMapper.selectList(wrapper).stream()
                .map(this::toResponse)
                .toList();
    }

    public KnowledgeBaseResponse createKnowledgeBase(KnowledgeBaseRequest request, Long userId) {
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setName(request.getName().trim());
        knowledgeBase.setDescription(request.getDescription());
        knowledgeBase.setOwnerId(userId);
        knowledgeBaseMapper.insert(knowledgeBase);
        return toResponse(knowledgeBase);
    }

    public KnowledgeBase getAuthorizedKnowledgeBase(Long id, Long userId, boolean admin) {
        KnowledgeBase knowledgeBase = knowledgeBaseMapper.selectById(id);
        if (knowledgeBase == null) {
            throw new IllegalArgumentException("知识库不存在");
        }
        if (!admin && !knowledgeBase.getOwnerId().equals(userId)) {
            throw new ForbiddenException("无权访问该知识库");
        }
        return knowledgeBase;
    }

    @Transactional
    public KnowledgeDocumentResponse uploadTextDocument(Long knowledgeBaseId,
                                                        String fileName,
                                                        String content,
                                                        Long userId,
                                                        boolean admin) {
        getAuthorizedKnowledgeBase(knowledgeBaseId, userId, admin);
        if (!StringUtils.hasText(content)) {
            throw new IllegalArgumentException("文档内容不能为空");
        }

        List<String> chunks = splitText(content);
        KnowledgeDocument document = new KnowledgeDocument();
        document.setKnowledgeBaseId(knowledgeBaseId);
        document.setOwnerId(userId);
        document.setFileName(StringUtils.hasText(fileName) ? fileName.trim() : "untitled.txt");
        document.setContentHash(textEmbeddingService.sha256(content));
        document.setChunkCount(chunks.size());
        knowledgeDocumentMapper.insert(document);

        List<List<Double>> embeddings = textEmbeddingService.embedBatch(chunks);
        for (int i = 0; i < chunks.size(); i++) {
            String chunkText = chunks.get(i);
            KnowledgeChunk chunk = new KnowledgeChunk();
            chunk.setKnowledgeBaseId(knowledgeBaseId);
            chunk.setDocumentId(document.getId());
            chunk.setChunkIndex(i);
            chunk.setContent(chunkText);
            chunk.setEmbedding(textEmbeddingService.serialize(embeddings.get(i)));
            applyEmbeddingMetadata(chunk);
            chunk.setTokenCount(estimateTokens(chunkText));
            knowledgeChunkMapper.insert(chunk);
        }

        return new KnowledgeDocumentResponse(
                document.getId(),
                document.getKnowledgeBaseId(),
                document.getFileName(),
                document.getChunkCount(),
                document.getCreatedAt()
        );
    }

    public List<KnowledgeDocumentResponse> listDocuments(Long knowledgeBaseId, Long userId, boolean admin) {
        getAuthorizedKnowledgeBase(knowledgeBaseId, userId, admin);
        return knowledgeDocumentMapper.selectList(new LambdaQueryWrapper<KnowledgeDocument>()
                        .eq(KnowledgeDocument::getKnowledgeBaseId, knowledgeBaseId)
                        .orderByDesc(KnowledgeDocument::getCreatedAt))
                .stream()
                .map(document -> new KnowledgeDocumentResponse(
                        document.getId(),
                        document.getKnowledgeBaseId(),
                        document.getFileName(),
                        document.getChunkCount(),
                        document.getCreatedAt()
                ))
                .toList();
    }

    public List<RetrievedChunk> retrieve(Long knowledgeBaseId, String query, int topK, double minScore) {
        if (knowledgeBaseId == null) {
            throw new IllegalArgumentException("RAG 节点缺少知识库配置");
        }
        if (!StringUtils.hasText(query)) {
            return List.of();
        }

        List<Double> queryEmbedding = textEmbeddingService.embed(query);
        return knowledgeChunkMapper.selectList(new LambdaQueryWrapper<KnowledgeChunk>()
                        .eq(KnowledgeChunk::getKnowledgeBaseId, knowledgeBaseId))
                .stream()
                .filter(this::isCompatibleEmbedding)
                .map(chunk -> new RetrievedChunk(
                        chunk.getId(),
                        chunk.getDocumentId(),
                        chunk.getChunkIndex(),
                        chunk.getContent(),
                        textEmbeddingService.cosine(queryEmbedding, textEmbeddingService.deserialize(chunk.getEmbedding()))
                ))
                .filter(chunk -> chunk.getScore() >= minScore)
                .sorted(Comparator.comparing(RetrievedChunk::getScore).reversed())
                .limit(Math.max(1, topK))
                .toList();
    }

    @Transactional
    public KnowledgeReindexResponse rebuildEmbeddings(Long knowledgeBaseId, Long userId, boolean admin) {
        getAuthorizedKnowledgeBase(knowledgeBaseId, userId, admin);
        List<KnowledgeChunk> chunks = knowledgeChunkMapper.selectList(new LambdaQueryWrapper<KnowledgeChunk>()
                .eq(KnowledgeChunk::getKnowledgeBaseId, knowledgeBaseId)
                .orderByAsc(KnowledgeChunk::getDocumentId)
                .orderByAsc(KnowledgeChunk::getChunkIndex));
        if (chunks.isEmpty()) {
            return new KnowledgeReindexResponse(
                    knowledgeBaseId,
                    0,
                    textEmbeddingService.provider(),
                    textEmbeddingService.model(),
                    textEmbeddingService.dimensions()
            );
        }

        List<List<Double>> embeddings = textEmbeddingService.embedBatch(chunks.stream()
                .map(KnowledgeChunk::getContent)
                .toList());
        for (int i = 0; i < chunks.size(); i++) {
            KnowledgeChunk chunk = chunks.get(i);
            chunk.setEmbedding(textEmbeddingService.serialize(embeddings.get(i)));
            applyEmbeddingMetadata(chunk);
            knowledgeChunkMapper.updateById(chunk);
        }

        return new KnowledgeReindexResponse(
                knowledgeBaseId,
                chunks.size(),
                textEmbeddingService.provider(),
                textEmbeddingService.model(),
                textEmbeddingService.dimensions()
        );
    }

    @Transactional
    public void deleteKnowledgeBase(Long id, Long userId, boolean admin) {
        getAuthorizedKnowledgeBase(id, userId, admin);
        knowledgeChunkMapper.delete(new LambdaQueryWrapper<KnowledgeChunk>().eq(KnowledgeChunk::getKnowledgeBaseId, id));
        knowledgeDocumentMapper.delete(new LambdaQueryWrapper<KnowledgeDocument>().eq(KnowledgeDocument::getKnowledgeBaseId, id));
        knowledgeBaseMapper.deleteById(id);
    }

    private KnowledgeBaseResponse toResponse(KnowledgeBase knowledgeBase) {
        Long documentCount = knowledgeDocumentMapper.selectCount(new LambdaQueryWrapper<KnowledgeDocument>()
                .eq(KnowledgeDocument::getKnowledgeBaseId, knowledgeBase.getId()));
        Long chunkCount = knowledgeChunkMapper.selectCount(new LambdaQueryWrapper<KnowledgeChunk>()
                .eq(KnowledgeChunk::getKnowledgeBaseId, knowledgeBase.getId()));
        return new KnowledgeBaseResponse(
                knowledgeBase.getId(),
                knowledgeBase.getName(),
                knowledgeBase.getDescription(),
                knowledgeBase.getOwnerId(),
                documentCount,
                chunkCount,
                knowledgeBase.getCreatedAt(),
                knowledgeBase.getUpdatedAt()
        );
    }

    private void applyEmbeddingMetadata(KnowledgeChunk chunk) {
        chunk.setEmbeddingProvider(textEmbeddingService.provider());
        chunk.setEmbeddingModel(textEmbeddingService.model());
        chunk.setEmbeddingDimension(textEmbeddingService.dimensions());
    }

    private boolean isCompatibleEmbedding(KnowledgeChunk chunk) {
        return textEmbeddingService.isCompatible(
                chunk.getEmbeddingProvider(),
                chunk.getEmbeddingModel(),
                chunk.getEmbeddingDimension()
        );
    }

    private List<String> splitText(String content) {
        String normalized = content.replace("\r\n", "\n").trim();
        List<String> chunks = new ArrayList<>();
        int start = 0;

        while (start < normalized.length()) {
            int end = Math.min(start + MAX_CHUNK_LENGTH, normalized.length());
            if (end < normalized.length()) {
                int punctuation = findLastBreak(normalized, start, end);
                if (punctuation > start + MAX_CHUNK_LENGTH / 2) {
                    end = punctuation + 1;
                }
            }

            String chunk = normalized.substring(start, end).trim();
            if (!chunk.isBlank()) {
                chunks.add(chunk);
            }

            if (end >= normalized.length()) {
                break;
            }
            start = Math.max(end - CHUNK_OVERLAP, start + 1);
        }

        return chunks;
    }

    private int findLastBreak(String text, int start, int end) {
        String breaks = "\n。！？；,.!?;";
        for (int i = end - 1; i >= start; i--) {
            if (breaks.indexOf(text.charAt(i)) >= 0) {
                return i;
            }
        }
        return -1;
    }

    private int estimateTokens(String text) {
        if (text == null) {
            return 0;
        }
        return Math.max(1, text.length() / 2);
    }
}
