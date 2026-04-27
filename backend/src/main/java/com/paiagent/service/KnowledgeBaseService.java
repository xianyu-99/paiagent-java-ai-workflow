package com.paiagent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.paiagent.common.ForbiddenException;
import com.paiagent.dto.KnowledgeBaseRequest;
import com.paiagent.dto.KnowledgeBaseResponse;
import com.paiagent.dto.KnowledgeChunkResponse;
import com.paiagent.dto.KnowledgeDocumentResponse;
import com.paiagent.dto.KnowledgeImportTaskResponse;
import com.paiagent.dto.KnowledgeReindexResponse;
import com.paiagent.dto.RetrievedChunk;
import com.paiagent.entity.KnowledgeBase;
import com.paiagent.entity.KnowledgeChunk;
import com.paiagent.entity.KnowledgeDocument;
import com.paiagent.entity.KnowledgeImportTask;
import com.paiagent.mapper.KnowledgeBaseMapper;
import com.paiagent.mapper.KnowledgeChunkMapper;
import com.paiagent.mapper.KnowledgeDocumentMapper;
import com.paiagent.mapper.KnowledgeImportTaskMapper;
import com.paiagent.service.document.DocumentParsingService;
import com.paiagent.service.document.ParsedDocument;
import com.paiagent.service.document.ParsedSegment;
import com.paiagent.service.rag.RagRetrievalScorer;
import com.paiagent.service.vector.KnowledgeVectorStoreService;
import com.paiagent.service.vector.VectorSearchHit;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class KnowledgeBaseService {

    private static final int MAX_CHUNK_LENGTH = 700;

    private static final int CHUNK_OVERLAP = 80;

    private static final int IMPORT_EMBEDDING_BATCH_SIZE = 16;

    private static final int MAX_RETRIEVAL_TOP_K = 10;

    private static final int DEFAULT_CONTEXT_WINDOW = 1;

    private static final int MAX_CONTEXT_WINDOW = 2;

    private static final int DEFAULT_CONTEXT_MAX_CHARS = 1800;

    private static final int MIN_CONTEXT_MAX_CHARS = 400;

    private static final int MAX_CONTEXT_MAX_CHARS = 6000;

    private final KnowledgeBaseMapper knowledgeBaseMapper;

    private final KnowledgeDocumentMapper knowledgeDocumentMapper;

    private final KnowledgeChunkMapper knowledgeChunkMapper;

    private final KnowledgeImportTaskMapper knowledgeImportTaskMapper;

    private final TextEmbeddingService textEmbeddingService;

    private final KnowledgeVectorStoreService knowledgeVectorStoreService;

    private final DocumentParsingService documentParsingService;

    private final RagRetrievalScorer ragRetrievalScorer;

    private final ThreadPoolTaskExecutor ragImportTaskExecutor;

    public KnowledgeBaseService(KnowledgeBaseMapper knowledgeBaseMapper,
                                KnowledgeDocumentMapper knowledgeDocumentMapper,
                                KnowledgeChunkMapper knowledgeChunkMapper,
                                KnowledgeImportTaskMapper knowledgeImportTaskMapper,
                                TextEmbeddingService textEmbeddingService,
                                KnowledgeVectorStoreService knowledgeVectorStoreService,
                                DocumentParsingService documentParsingService,
                                RagRetrievalScorer ragRetrievalScorer,
                                @Qualifier("ragImportTaskExecutor") ThreadPoolTaskExecutor ragImportTaskExecutor) {
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.knowledgeDocumentMapper = knowledgeDocumentMapper;
        this.knowledgeChunkMapper = knowledgeChunkMapper;
        this.knowledgeImportTaskMapper = knowledgeImportTaskMapper;
        this.textEmbeddingService = textEmbeddingService;
        this.knowledgeVectorStoreService = knowledgeVectorStoreService;
        this.documentParsingService = documentParsingService;
        this.ragRetrievalScorer = ragRetrievalScorer;
        this.ragImportTaskExecutor = ragImportTaskExecutor;
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
        if (!admin && (knowledgeBase.getOwnerId() == null || !knowledgeBase.getOwnerId().equals(userId))) {
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
        return saveParsedDocument(knowledgeBaseId, documentParsingService.parseText(fileName, content), userId, ImportProgressReporter.noop());
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
        return saveParsedDocument(knowledgeBaseId, parsedDocument, userId, ImportProgressReporter.noop());
    }

    public KnowledgeImportTaskResponse startTextImport(Long knowledgeBaseId,
                                                       String fileName,
                                                       String content,
                                                       Long userId,
                                                       boolean admin) {
        getAuthorizedKnowledgeBase(knowledgeBaseId, userId, admin);
        if (!StringUtils.hasText(content)) {
            throw new IllegalArgumentException("文档内容不能为空");
        }
        KnowledgeImportTask task = createImportTask(
                knowledgeBaseId,
                userId,
                StringUtils.hasText(fileName) ? fileName.trim() : "manual.txt",
                documentParsingService.detectContentType(fileName, null)
        );
        ragImportTaskExecutor.execute(() -> runImportTask(
                task.getId(),
                userId,
                () -> documentParsingService.parseText(fileName, content)
        ));
        return toImportTaskResponse(task);
    }

    public KnowledgeImportTaskResponse startFileImport(Long knowledgeBaseId,
                                                       String fileName,
                                                       String contentType,
                                                       byte[] bytes,
                                                       Long userId,
                                                       boolean admin) {
        getAuthorizedKnowledgeBase(knowledgeBaseId, userId, admin);
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("文档内容不能为空");
        }
        KnowledgeImportTask task = createImportTask(
                knowledgeBaseId,
                userId,
                StringUtils.hasText(fileName) ? fileName.trim() : "untitled.txt",
                documentParsingService.detectContentType(fileName, contentType)
        );
        ragImportTaskExecutor.execute(() -> runImportTask(
                task.getId(),
                userId,
                () -> documentParsingService.parseFile(fileName, contentType, bytes)
        ));
        return toImportTaskResponse(task);
    }

    public KnowledgeImportTaskResponse getImportTask(Long knowledgeBaseId,
                                                     Long taskId,
                                                     Long userId,
                                                     boolean admin) {
        getAuthorizedKnowledgeBase(knowledgeBaseId, userId, admin);
        KnowledgeImportTask task = knowledgeImportTaskMapper.selectById(taskId);
        if (task == null || !knowledgeBaseId.equals(task.getKnowledgeBaseId())) {
            throw new IllegalArgumentException("导入任务不存在");
        }
        if (!admin && !task.getOwnerId().equals(userId)) {
            throw new ForbiddenException("无权访问该导入任务");
        }
        return toImportTaskResponse(task);
    }

    public List<KnowledgeImportTaskResponse> listImportTasks(Long knowledgeBaseId,
                                                             Long userId,
                                                             boolean admin) {
        getAuthorizedKnowledgeBase(knowledgeBaseId, userId, admin);
        LambdaQueryWrapper<KnowledgeImportTask> wrapper = new LambdaQueryWrapper<KnowledgeImportTask>()
                .eq(KnowledgeImportTask::getKnowledgeBaseId, knowledgeBaseId)
                .orderByDesc(KnowledgeImportTask::getUpdatedAt)
                .last("LIMIT 10");
        if (!admin) {
            wrapper.eq(KnowledgeImportTask::getOwnerId, userId);
        }
        return knowledgeImportTaskMapper.selectList(wrapper)
                .stream()
                .map(this::toImportTaskResponse)
                .toList();
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
        return retrieve(knowledgeBaseId, query, topK, minScore, DEFAULT_CONTEXT_WINDOW, DEFAULT_CONTEXT_MAX_CHARS);
    }

    public List<RetrievedChunk> retrieve(Long knowledgeBaseId,
                                         String query,
                                         int topK,
                                         double minScore,
                                         int contextWindow,
                                         int contextMaxChars) {
        if (knowledgeBaseId == null) {
            throw new IllegalArgumentException("RAG 节点缺少知识库配置");
        }
        if (!StringUtils.hasText(query)) {
            return List.of();
        }

        int safeTopK = Math.min(MAX_RETRIEVAL_TOP_K, Math.max(1, topK));
        int safeContextWindow = Math.max(0, Math.min(MAX_CONTEXT_WINDOW, contextWindow));
        int safeContextMaxChars = Math.max(MIN_CONTEXT_MAX_CHARS, Math.min(MAX_CONTEXT_MAX_CHARS, contextMaxChars));
        int candidateLimit = Math.max(safeTopK * 6, safeTopK + 12);
        List<Double> queryEmbedding = textEmbeddingService.embed(query);
        List<VectorSearchHit> vectorHits = knowledgeVectorStoreService.search(knowledgeBaseId, queryEmbedding, candidateLimit, 0.0);
        List<KnowledgeChunk> keywordHits = searchKeywordCandidates(knowledgeBaseId, query, candidateLimit);

        Map<Long, RetrievalCandidate> candidates = new LinkedHashMap<>();
        for (VectorSearchHit hit : vectorHits) {
            candidates.computeIfAbsent(hit.chunkId(), RetrievalCandidate::new)
                    .vectorScore(hit.score());
        }
        for (KnowledgeChunk chunk : keywordHits) {
            candidates.computeIfAbsent(chunk.getId(), RetrievalCandidate::new)
                    .chunk(chunk)
                    .keywordScore(ragRetrievalScorer.keywordScore(query, chunk));
        }
        if (candidates.isEmpty()) {
            return List.of();
        }

        Set<Long> missingChunkIds = candidates.values().stream()
                .filter(candidate -> candidate.chunk() == null)
                .map(RetrievalCandidate::chunkId)
                .collect(Collectors.toCollection(HashSet::new));
        if (!missingChunkIds.isEmpty()) {
            Map<Long, KnowledgeChunk> chunkMap = knowledgeChunkMapper.selectBatchIds(missingChunkIds)
                    .stream()
                    .filter(this::isCompatibleEmbedding)
                    .collect(Collectors.toMap(
                            KnowledgeChunk::getId,
                            Function.identity(),
                            (left, right) -> left,
                            LinkedHashMap::new
                    ));
            for (RetrievalCandidate candidate : candidates.values()) {
                if (candidate.chunk() == null) {
                    candidate.chunk(chunkMap.get(candidate.chunkId()));
                }
            }
        }

        List<RetrievalCandidate> rankedCandidates = candidates.values().stream()
                .filter(candidate -> candidate.chunk() != null)
                .peek(candidate -> {
                    if (candidate.keywordScore() == null) {
                        candidate.keywordScore(ragRetrievalScorer.keywordScore(query, candidate.chunk()));
                    }
                    candidate.rerankScore(ragRetrievalScorer.rerankScore(
                            candidate.vectorScore() == null ? 0.0 : candidate.vectorScore(),
                            candidate.keywordScore() == null ? 0.0 : candidate.keywordScore()
                    ));
                })
                .filter(candidate -> candidate.rerankScore() >= minScore)
                .sorted((left, right) -> Double.compare(right.rerankScore(), left.rerankScore()))
                .limit(safeTopK)
                .toList();

        enrichRetrievedCandidates(rankedCandidates, query, safeContextWindow, safeContextMaxChars);
        return rankedCandidates.stream()
                .map(this::toRetrievedChunk)
                .toList();
    }

    public List<RetrievedChunk> retrieveAuthorized(Long knowledgeBaseId,
                                                   String query,
                                                   int topK,
                                                   double minScore,
                                                   Long userId,
                                                   boolean admin) {
        getAuthorizedKnowledgeBase(knowledgeBaseId, userId, admin);
        return retrieve(knowledgeBaseId, query, topK, minScore);
    }

    public List<RetrievedChunk> retrieveAuthorized(Long knowledgeBaseId,
                                                   String query,
                                                   int topK,
                                                   double minScore,
                                                   int contextWindow,
                                                   int contextMaxChars,
                                                   Long userId,
                                                   boolean admin) {
        getAuthorizedKnowledgeBase(knowledgeBaseId, userId, admin);
        return retrieve(knowledgeBaseId, query, topK, minScore, contextWindow, contextMaxChars);
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
        knowledgeImportTaskMapper.delete(new LambdaQueryWrapper<KnowledgeImportTask>().eq(KnowledgeImportTask::getKnowledgeBaseId, id));
        knowledgeBaseMapper.deleteById(id);
    }

    private KnowledgeImportTask createImportTask(Long knowledgeBaseId,
                                                 Long userId,
                                                 String fileName,
                                                 String contentType) {
        KnowledgeImportTask task = new KnowledgeImportTask();
        task.setKnowledgeBaseId(knowledgeBaseId);
        task.setOwnerId(userId);
        task.setFileName(fileName);
        task.setContentType(contentType);
        task.setStatus("PENDING");
        task.setStage("等待导入");
        task.setProgress(0);
        task.setTotalChunks(0);
        task.setProcessedChunks(0);
        knowledgeImportTaskMapper.insert(task);
        return task;
    }

    private void runImportTask(Long taskId,
                               Long userId,
                               ParsedDocumentSupplier parsedDocumentSupplier) {
        updateImportTask(taskId, "RUNNING", "解析文档中", 5, null, null, null, null);
        try {
            KnowledgeImportTask task = knowledgeImportTaskMapper.selectById(taskId);
            if (task == null) {
                return;
            }

            ParsedDocument parsedDocument = parsedDocumentSupplier.get();
            if (!StringUtils.hasText(parsedDocument.rawText())) {
                throw new IllegalArgumentException("文档解析后没有可导入文本");
            }

            updateImportTask(taskId, "RUNNING", "文档解析完成，准备切片", 15, null, null, null, null);
            KnowledgeDocumentResponse document = saveParsedDocument(
                    task.getKnowledgeBaseId(),
                    parsedDocument,
                    userId,
                    new TaskImportProgressReporter(taskId)
            );
            updateImportTask(taskId, "SUCCESS", "导入完成", 100,
                    document.getChunkCount(), document.getChunkCount(), document.getId(), null);
        } catch (Exception e) {
            cleanupFailedImport(taskId);
            updateImportTask(taskId, "FAILED", "导入失败", 100, null, null, null, e.getMessage());
        }
    }

    private void updateImportTask(Long taskId,
                                  String status,
                                  String stage,
                                  Integer progress,
                                  Integer totalChunks,
                                  Integer processedChunks,
                                  Long documentId,
                                  String errorMessage) {
        KnowledgeImportTask task = knowledgeImportTaskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        if (StringUtils.hasText(status)) {
            task.setStatus(status);
        }
        if (StringUtils.hasText(stage)) {
            task.setStage(stage);
        }
        if (progress != null) {
            task.setProgress(Math.max(0, Math.min(100, progress)));
        }
        if (totalChunks != null) {
            task.setTotalChunks(totalChunks);
        }
        if (processedChunks != null) {
            task.setProcessedChunks(processedChunks);
        }
        if (documentId != null) {
            task.setDocumentId(documentId);
        }
        if (errorMessage != null) {
            task.setErrorMessage(errorMessage);
        }
        if ("SUCCESS".equals(status) || "FAILED".equals(status)) {
            task.setCompletedAt(LocalDateTime.now());
        }
        knowledgeImportTaskMapper.updateById(task);
    }

    private void cleanupFailedImport(Long taskId) {
        KnowledgeImportTask task = knowledgeImportTaskMapper.selectById(taskId);
        if (task == null || task.getDocumentId() == null) {
            return;
        }
        knowledgeChunkMapper.delete(new LambdaQueryWrapper<KnowledgeChunk>()
                .eq(KnowledgeChunk::getDocumentId, task.getDocumentId()));
        knowledgeDocumentMapper.deleteById(task.getDocumentId());
    }

    private KnowledgeDocumentResponse saveParsedDocument(Long knowledgeBaseId,
                                                         ParsedDocument parsedDocument,
                                                         Long userId,
                                                         ImportProgressReporter progressReporter) {
        List<ChunkDraft> chunks = splitParsedDocument(parsedDocument);
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("文档解析后没有可导入文本");
        }
        progressReporter.update("文档切片完成", 25, chunks.size(), 0);

        KnowledgeDocument document = new KnowledgeDocument();
        document.setKnowledgeBaseId(knowledgeBaseId);
        document.setOwnerId(userId);
        document.setFileName(parsedDocument.fileName());
        document.setContentType(parsedDocument.contentType());
        document.setParserType(parsedDocument.parserType());
        document.setContentHash(textEmbeddingService.sha256(parsedDocument.rawText()));
        document.setChunkCount(chunks.size());
        knowledgeDocumentMapper.insert(document);
        progressReporter.documentCreated(document.getId());

        List<KnowledgeChunk> insertedChunks = new ArrayList<>();
        int processed = 0;
        for (int start = 0; start < chunks.size(); start += IMPORT_EMBEDDING_BATCH_SIZE) {
            int end = Math.min(start + IMPORT_EMBEDDING_BATCH_SIZE, chunks.size());
            List<ChunkDraft> batchDrafts = chunks.subList(start, end);
            List<List<Double>> embeddings = textEmbeddingService.embedBatch(batchDrafts.stream()
                    .map(ChunkDraft::content)
                    .toList());

            List<KnowledgeChunk> batchChunks = new ArrayList<>();
            for (int i = 0; i < batchDrafts.size(); i++) {
                int chunkIndex = start + i;
                ChunkDraft draft = batchDrafts.get(i);
                KnowledgeChunk chunk = new KnowledgeChunk();
                chunk.setKnowledgeBaseId(knowledgeBaseId);
                chunk.setDocumentId(document.getId());
                chunk.setChunkIndex(chunkIndex);
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
                batchChunks.add(chunk);
            }
            knowledgeVectorStoreService.upsert(batchChunks);
            insertedChunks.addAll(batchChunks);
            processed += batchChunks.size();
            int progress = 30 + (int) Math.round(65.0 * processed / chunks.size());
            progressReporter.update("向量化、入库与索引写入中", Math.min(progress, 95), chunks.size(), processed);
        }
        progressReporter.update("导入完成", 100, chunks.size(), insertedChunks.size());

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

    private KnowledgeImportTaskResponse toImportTaskResponse(KnowledgeImportTask task) {
        return new KnowledgeImportTaskResponse(
                task.getId(),
                task.getKnowledgeBaseId(),
                task.getDocumentId(),
                task.getFileName(),
                task.getContentType(),
                task.getStatus(),
                task.getStage(),
                task.getProgress(),
                task.getTotalChunks(),
                task.getProcessedChunks(),
                task.getErrorMessage(),
                task.getCreatedAt(),
                task.getUpdatedAt(),
                task.getCompletedAt()
        );
    }

    private List<KnowledgeChunk> searchKeywordCandidates(Long knowledgeBaseId, String query, int limit) {
        List<String> terms = ragRetrievalScorer.searchTerms(query);
        if (terms.isEmpty()) {
            return List.of();
        }

        LambdaQueryWrapper<KnowledgeChunk> wrapper = new LambdaQueryWrapper<KnowledgeChunk>()
                .eq(KnowledgeChunk::getKnowledgeBaseId, knowledgeBaseId)
                .and(nested -> {
                    for (int i = 0; i < terms.size(); i++) {
                        String term = terms.get(i);
                        if (i > 0) {
                            nested.or();
                        }
                        nested.and(termWrapper -> termWrapper
                                .like(KnowledgeChunk::getContent, term)
                                .or()
                                .like(KnowledgeChunk::getSourceName, term)
                                .or()
                                .like(KnowledgeChunk::getSectionTitle, term));
                    }
                })
                .last("LIMIT " + Math.max(1, limit));
        return knowledgeChunkMapper.selectList(wrapper);
    }

    private void enrichRetrievedCandidates(List<RetrievalCandidate> candidates,
                                           String query,
                                           int contextWindow,
                                           int contextMaxChars) {
        int rank = 1;
        for (RetrievalCandidate candidate : candidates) {
            candidate.rank(rank++);
            candidate.matchedTerms(ragRetrievalScorer.matchedTerms(query, candidate.chunk()));

            List<KnowledgeChunk> contextChunks = loadContextWindow(candidate.chunk(), contextWindow);
            candidate.contextChunkIndexes(contextChunks.stream()
                    .map(KnowledgeChunk::getChunkIndex)
                    .toList());
            candidate.contextContent(buildContextContent(contextChunks, contextMaxChars));
        }
    }

    private List<KnowledgeChunk> loadContextWindow(KnowledgeChunk centerChunk, int contextWindow) {
        if (centerChunk == null) {
            return List.of();
        }
        if (contextWindow <= 0 || centerChunk.getDocumentId() == null || centerChunk.getChunkIndex() == null) {
            return List.of(centerChunk);
        }

        int startIndex = Math.max(0, centerChunk.getChunkIndex() - contextWindow);
        int endIndex = centerChunk.getChunkIndex() + contextWindow;
        List<KnowledgeChunk> contextChunks = knowledgeChunkMapper.selectList(new LambdaQueryWrapper<KnowledgeChunk>()
                .eq(KnowledgeChunk::getKnowledgeBaseId, centerChunk.getKnowledgeBaseId())
                .eq(KnowledgeChunk::getDocumentId, centerChunk.getDocumentId())
                .between(KnowledgeChunk::getChunkIndex, startIndex, endIndex)
                .orderByAsc(KnowledgeChunk::getChunkIndex));
        return contextChunks.isEmpty() ? List.of(centerChunk) : contextChunks;
    }

    private String buildContextContent(List<KnowledgeChunk> contextChunks, int contextMaxChars) {
        String context = contextChunks.stream()
                .map(chunk -> "【片段 " + (chunk.getChunkIndex() + 1) + "】\n" + chunk.getContent())
                .collect(Collectors.joining("\n\n"));
        return abbreviate(context, contextMaxChars);
    }

    private String abbreviate(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        int end = Math.max(0, maxChars - 12);
        return text.substring(0, end).trim() + "\n...(已截断)";
    }

    private RetrievedChunk toRetrievedChunk(RetrievalCandidate candidate) {
        KnowledgeChunk chunk = candidate.chunk();
        return new RetrievedChunk(
                chunk.getId(),
                chunk.getDocumentId(),
                chunk.getChunkIndex(),
                chunk.getContent(),
                chunk.getSourceName(),
                chunk.getSectionTitle(),
                chunk.getPageNumber(),
                candidate.rerankScore(),
                candidate.vectorScore() == null ? 0.0 : candidate.vectorScore(),
                candidate.keywordScore() == null ? 0.0 : candidate.keywordScore(),
                candidate.rank(),
                candidate.matchedTerms(),
                candidate.contextContent(),
                candidate.contextChunkIndexes()
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

    @FunctionalInterface
    private interface ParsedDocumentSupplier {
        ParsedDocument get() throws Exception;
    }

    private interface ImportProgressReporter {
        void update(String stage, int progress, Integer totalChunks, Integer processedChunks);

        default void documentCreated(Long documentId) {
        }

        static ImportProgressReporter noop() {
            return (stage, progress, totalChunks, processedChunks) -> {
            };
        }
    }

    private class TaskImportProgressReporter implements ImportProgressReporter {

        private final Long taskId;

        private TaskImportProgressReporter(Long taskId) {
            this.taskId = taskId;
        }

        @Override
        public void update(String stage, int progress, Integer totalChunks, Integer processedChunks) {
            updateImportTask(taskId, "RUNNING", stage, progress, totalChunks, processedChunks, null, null);
        }

        @Override
        public void documentCreated(Long documentId) {
            updateImportTask(taskId, "RUNNING", "文档记录已创建", 28, null, null, documentId, null);
        }
    }

    private static class RetrievalCandidate {

        private final Long chunkId;

        private KnowledgeChunk chunk;

        private Double vectorScore = 0.0;

        private Double keywordScore = 0.0;

        private Double rerankScore = 0.0;

        private Integer rank = 0;

        private List<String> matchedTerms = List.of();

        private String contextContent = "";

        private List<Integer> contextChunkIndexes = List.of();

        private RetrievalCandidate(Long chunkId) {
            this.chunkId = chunkId;
        }

        private Long chunkId() {
            return chunkId;
        }

        private KnowledgeChunk chunk() {
            return chunk;
        }

        private RetrievalCandidate chunk(KnowledgeChunk chunk) {
            this.chunk = chunk;
            return this;
        }

        private Double vectorScore() {
            return vectorScore;
        }

        private RetrievalCandidate vectorScore(Double vectorScore) {
            this.vectorScore = vectorScore;
            return this;
        }

        private Double keywordScore() {
            return keywordScore;
        }

        private RetrievalCandidate keywordScore(Double keywordScore) {
            this.keywordScore = keywordScore;
            return this;
        }

        private Double rerankScore() {
            return rerankScore;
        }

        private RetrievalCandidate rerankScore(Double rerankScore) {
            this.rerankScore = rerankScore;
            return this;
        }

        private Integer rank() {
            return rank;
        }

        private RetrievalCandidate rank(Integer rank) {
            this.rank = rank;
            return this;
        }

        private List<String> matchedTerms() {
            return matchedTerms;
        }

        private RetrievalCandidate matchedTerms(List<String> matchedTerms) {
            this.matchedTerms = matchedTerms == null ? List.of() : matchedTerms;
            return this;
        }

        private String contextContent() {
            return contextContent;
        }

        private RetrievalCandidate contextContent(String contextContent) {
            this.contextContent = contextContent == null ? "" : contextContent;
            return this;
        }

        private List<Integer> contextChunkIndexes() {
            return contextChunkIndexes;
        }

        private RetrievalCandidate contextChunkIndexes(List<Integer> contextChunkIndexes) {
            this.contextChunkIndexes = contextChunkIndexes == null ? List.of() : contextChunkIndexes;
            return this;
        }
    }
}
