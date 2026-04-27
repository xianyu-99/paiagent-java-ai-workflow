package com.paiagent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.paiagent.common.ForbiddenException;
import com.paiagent.dto.KnowledgeBaseRequest;
import com.paiagent.dto.KnowledgeBaseResponse;
import com.paiagent.dto.KnowledgeChunkResponse;
import com.paiagent.dto.KnowledgeDocumentResponse;
import com.paiagent.dto.KnowledgeReindexResponse;
import com.paiagent.dto.RetrievedChunk;
import com.paiagent.entity.KnowledgeBase;
import com.paiagent.entity.KnowledgeChunk;
import com.paiagent.entity.KnowledgeDocument;
import com.paiagent.mapper.KnowledgeBaseMapper;
import com.paiagent.mapper.KnowledgeChunkMapper;
import com.paiagent.mapper.KnowledgeDocumentMapper;
import com.paiagent.service.document.DocumentParsingService;
import com.paiagent.service.document.ParsedDocument;
import com.paiagent.service.document.ParsedSegment;
import com.paiagent.service.vector.KnowledgeVectorStoreService;
import com.paiagent.service.vector.VectorSearchHit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class KnowledgeBaseService {

    private static final int MAX_CHUNK_LENGTH = 700;

    private static final int CHUNK_OVERLAP = 80;

    private final KnowledgeBaseMapper knowledgeBaseMapper;

    private final KnowledgeDocumentMapper knowledgeDocumentMapper;

    private final KnowledgeChunkMapper knowledgeChunkMapper;

    private final TextEmbeddingService textEmbeddingService;

    private final KnowledgeVectorStoreService knowledgeVectorStoreService;

    private final DocumentParsingService documentParsingService;

    public KnowledgeBaseService(KnowledgeBaseMapper knowledgeBaseMapper,
                                KnowledgeDocumentMapper knowledgeDocumentMapper,
                                KnowledgeChunkMapper knowledgeChunkMapper,
                                TextEmbeddingService textEmbeddingService,
                                KnowledgeVectorStoreService knowledgeVectorStoreService,
                                DocumentParsingService documentParsingService) {
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.knowledgeDocumentMapper = knowledgeDocumentMapper;
        this.knowledgeChunkMapper = knowledgeChunkMapper;
        this.textEmbeddingService = textEmbeddingService;
        this.knowledgeVectorStoreService = knowledgeVectorStoreService;
        this.documentParsingService = documentParsingService;
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
        return saveParsedDocument(knowledgeBaseId, documentParsingService.parseText(fileName, content), userId);
    }

    @Transactional
    public KnowledgeDocumentResponse uploadFileDocument(Long knowledgeBaseId,
                                                        String fileName,
                                                        String contentType,
                                                        byte[] bytes,
                                                        Long userId,
                                                        boolean admin) {
        getAuthorizedKnowledgeBase(knowledgeBaseId, userId, admin);
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("文档内容不能为空");
        }
        ParsedDocument parsedDocument = documentParsingService.parseFile(fileName, contentType, bytes);
        if (!StringUtils.hasText(parsedDocument.rawText())) {
            throw new IllegalArgumentException("文档解析后没有可导入文本");
        }
        return saveParsedDocument(knowledgeBaseId, parsedDocument, userId);
    }

    public List<KnowledgeDocumentResponse> listDocuments(Long knowledgeBaseId, Long userId, boolean admin) {
        getAuthorizedKnowledgeBase(knowledgeBaseId, userId, admin);
        return knowledgeDocumentMapper.selectList(new LambdaQueryWrapper<KnowledgeDocument>()
                        .eq(KnowledgeDocument::getKnowledgeBaseId, knowledgeBaseId)
                        .orderByDesc(KnowledgeDocument::getCreatedAt))
                .stream()
                .map(this::toDocumentResponse)
                .toList();
    }

    public List<KnowledgeChunkResponse> listChunks(Long knowledgeBaseId,
                                                   Long documentId,
                                                   Long userId,
                                                   boolean admin) {
        getAuthorizedKnowledgeBase(knowledgeBaseId, userId, admin);
        return knowledgeChunkMapper.selectList(new LambdaQueryWrapper<KnowledgeChunk>()
                        .eq(KnowledgeChunk::getKnowledgeBaseId, knowledgeBaseId)
                        .eq(KnowledgeChunk::getDocumentId, documentId)
                        .orderByAsc(KnowledgeChunk::getChunkIndex))
                .stream()
                .map(this::toChunkResponse)
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
        List<VectorSearchHit> hits = knowledgeVectorStoreService.search(knowledgeBaseId, queryEmbedding, topK, minScore);
        if (hits.isEmpty()) {
            return List.of();
        }

        Map<Long, KnowledgeChunk> chunkMap = knowledgeChunkMapper.selectBatchIds(hits.stream()
                        .map(VectorSearchHit::chunkId)
                        .toList())
                .stream()
                .filter(this::isCompatibleEmbedding)
                .collect(Collectors.toMap(
                        KnowledgeChunk::getId,
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));

        List<RetrievedChunk> retrievedChunks = new ArrayList<>();
        for (VectorSearchHit hit : hits) {
            KnowledgeChunk chunk = chunkMap.get(hit.chunkId());
            if (chunk == null) {
                continue;
            }
            retrievedChunks.add(new RetrievedChunk(
                    chunk.getId(),
                    chunk.getDocumentId(),
                    chunk.getChunkIndex(),
                    chunk.getContent(),
                    hit.score()
            ));
        }
        return retrievedChunks;
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
        knowledgeVectorStoreService.upsert(chunks);

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
        knowledgeVectorStoreService.deleteKnowledgeBase(id);
        knowledgeChunkMapper.delete(new LambdaQueryWrapper<KnowledgeChunk>().eq(KnowledgeChunk::getKnowledgeBaseId, id));
        knowledgeDocumentMapper.delete(new LambdaQueryWrapper<KnowledgeDocument>().eq(KnowledgeDocument::getKnowledgeBaseId, id));
        knowledgeBaseMapper.deleteById(id);
    }

    private KnowledgeDocumentResponse saveParsedDocument(Long knowledgeBaseId,
                                                         ParsedDocument parsedDocument,
                                                         Long userId) {
        List<ChunkDraft> chunks = splitParsedDocument(parsedDocument);
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("文档解析后没有可导入文本");
        }

        KnowledgeDocument document = new KnowledgeDocument();
        document.setKnowledgeBaseId(knowledgeBaseId);
        document.setOwnerId(userId);
        document.setFileName(parsedDocument.fileName());
        document.setContentType(parsedDocument.contentType());
        document.setParserType(parsedDocument.parserType());
        document.setContentHash(textEmbeddingService.sha256(parsedDocument.rawText()));
        document.setChunkCount(chunks.size());
        knowledgeDocumentMapper.insert(document);

        List<List<Double>> embeddings = textEmbeddingService.embedBatch(chunks.stream()
                .map(ChunkDraft::content)
                .toList());
        List<KnowledgeChunk> insertedChunks = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            ChunkDraft draft = chunks.get(i);
            KnowledgeChunk chunk = new KnowledgeChunk();
            chunk.setKnowledgeBaseId(knowledgeBaseId);
            chunk.setDocumentId(document.getId());
            chunk.setChunkIndex(i);
            chunk.setContent(draft.content());
            chunk.setSourceName(draft.sourceName());
            chunk.setContentType(draft.contentType());
            chunk.setSectionTitle(draft.sectionTitle());
            chunk.setPageNumber(draft.pageNumber());
            chunk.setStartOffset(draft.startOffset());
            chunk.setEndOffset(draft.endOffset());
            chunk.setEmbedding(textEmbeddingService.serialize(embeddings.get(i)));
            applyEmbeddingMetadata(chunk);
            chunk.setTokenCount(estimateTokens(draft.content()));
            knowledgeChunkMapper.insert(chunk);
            insertedChunks.add(chunk);
        }
        knowledgeVectorStoreService.upsert(insertedChunks);

        return toDocumentResponse(document);
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

    private KnowledgeDocumentResponse toDocumentResponse(KnowledgeDocument document) {
        return new KnowledgeDocumentResponse(
                document.getId(),
                document.getKnowledgeBaseId(),
                document.getFileName(),
                document.getContentType(),
                document.getParserType(),
                document.getChunkCount(),
                document.getCreatedAt()
        );
    }

    private KnowledgeChunkResponse toChunkResponse(KnowledgeChunk chunk) {
        return new KnowledgeChunkResponse(
                chunk.getId(),
                chunk.getDocumentId(),
                chunk.getChunkIndex(),
                chunk.getContent(),
                chunk.getSourceName(),
                chunk.getContentType(),
                chunk.getSectionTitle(),
                chunk.getPageNumber(),
                chunk.getStartOffset(),
                chunk.getEndOffset(),
                chunk.getTokenCount(),
                chunk.getCreatedAt()
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

    private List<ChunkDraft> splitParsedDocument(ParsedDocument parsedDocument) {
        List<ParsedSegment> segments = parsedDocument.segments();
        if (segments == null || segments.isEmpty()) {
            segments = List.of(new ParsedSegment(
                    parsedDocument.rawText(),
                    parsedDocument.fileName(),
                    parsedDocument.contentType(),
                    null,
                    null,
                    0,
                    parsedDocument.rawText() == null ? 0 : parsedDocument.rawText().length()
            ));
        }

        List<ChunkDraft> chunks = new ArrayList<>();
        for (ParsedSegment segment : segments) {
            chunks.addAll(splitSegment(segment));
        }
        return chunks;
    }

    private List<ChunkDraft> splitSegment(ParsedSegment segment) {
        String normalized = segment.text() == null ? "" : segment.text().replace("\r\n", "\n").trim();
        List<ChunkDraft> chunks = new ArrayList<>();
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
                int absoluteStart = (segment.startOffset() == null ? 0 : segment.startOffset()) + start;
                chunks.add(new ChunkDraft(
                        chunk,
                        segment.sourceName(),
                        segment.contentType(),
                        segment.sectionTitle(),
                        segment.pageNumber(),
                        absoluteStart,
                        absoluteStart + chunk.length()
                ));
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

    private record ChunkDraft(
            String content,
            String sourceName,
            String contentType,
            String sectionTitle,
            Integer pageNumber,
            Integer startOffset,
            Integer endOffset
    ) {
    }
}
