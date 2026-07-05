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
import com.paiagent.engine.rerank.Reranker;
import com.paiagent.engine.rerank.RerankerFactory;
import com.paiagent.engine.retrieval.BM25Scorer;
import com.paiagent.engine.retrieval.HybridRetriever;
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
import com.paiagent.service.graph.GraphEvidence;
import com.paiagent.service.graph.KnowledgeGraphService;
import com.paiagent.service.rag.RagRetrievalScorer;
import com.paiagent.service.rag.RetrievalPersonalization;
import com.paiagent.service.rag.RetrievalCandidate;
import com.paiagent.service.vector.KnowledgeVectorStore;
import com.paiagent.service.vector.VectorSearchHit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import com.baomidou.mybatisplus.extension.toolkit.Db;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
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

    /**
     * Score threshold applied at the (post-fusion / post-rerank) stage.
     * The vector recall stage intentionally uses score_threshold=0 so that
     * keyword-only matches (vectorScore=0) are not dropped before fusion.
     */
    private static final double VECTOR_RECALL_SCORE_THRESHOLD = 0.0;

    private final KnowledgeBaseMapper knowledgeBaseMapper;

    private final KnowledgeDocumentMapper knowledgeDocumentMapper;

    private final KnowledgeChunkMapper knowledgeChunkMapper;

    private final KnowledgeImportTaskMapper knowledgeImportTaskMapper;

    private final TextEmbeddingService textEmbeddingService;

    private final KnowledgeVectorStore knowledgeVectorStore;

    private final DocumentParsingService documentParsingService;

    private final RagRetrievalScorer ragRetrievalScorer;

    private final ThreadPoolTaskExecutor ragImportTaskExecutor;

    private final HybridRetriever hybridRetriever;

    private final BM25Scorer bm25Scorer;

    private final RerankerFactory rerankerFactory;

    private final KnowledgeGraphService knowledgeGraphService;

    private final UserRetrievalProfileService userRetrievalProfileService;

    @Autowired
    public KnowledgeBaseService(KnowledgeBaseMapper knowledgeBaseMapper,
                                KnowledgeDocumentMapper knowledgeDocumentMapper,
                                KnowledgeChunkMapper knowledgeChunkMapper,
                                KnowledgeImportTaskMapper knowledgeImportTaskMapper,
                                TextEmbeddingService textEmbeddingService,
                                KnowledgeVectorStore knowledgeVectorStore,
                                DocumentParsingService documentParsingService,
                                RagRetrievalScorer ragRetrievalScorer,
                                @Qualifier("ragImportTaskExecutor") ThreadPoolTaskExecutor ragImportTaskExecutor,
                                ObjectProvider<HybridRetriever> hybridRetrieverProvider,
                                ObjectProvider<BM25Scorer> bm25ScorerProvider,
                                ObjectProvider<RerankerFactory> rerankerFactoryProvider,
                                ObjectProvider<KnowledgeGraphService> knowledgeGraphServiceProvider,
                                ObjectProvider<UserRetrievalProfileService> userRetrievalProfileServiceProvider) {
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.knowledgeDocumentMapper = knowledgeDocumentMapper;
        this.knowledgeChunkMapper = knowledgeChunkMapper;
        this.knowledgeImportTaskMapper = knowledgeImportTaskMapper;
        this.textEmbeddingService = textEmbeddingService;
        this.knowledgeVectorStore = knowledgeVectorStore;
        this.documentParsingService = documentParsingService;
        this.ragRetrievalScorer = ragRetrievalScorer;
        this.ragImportTaskExecutor = ragImportTaskExecutor;
        this.hybridRetriever = hybridRetrieverProvider.getIfAvailable();
        this.bm25Scorer = bm25ScorerProvider.getIfAvailable();
        this.rerankerFactory = rerankerFactoryProvider.getIfAvailable();
        this.knowledgeGraphService = knowledgeGraphServiceProvider.getIfAvailable();
        this.userRetrievalProfileService = userRetrievalProfileServiceProvider.getIfAvailable();
    }

    /**
     * Legacy constructor used by unit tests that pre-date the industrial retrieval pipeline.
     * RRF and rerank dependencies are null, so {@link #rankCandidates} transparently falls
     * back to the linear fusion path. This keeps existing tests compiling and green without
     * forcing every test to mock the new collaborators.
     */
    public KnowledgeBaseService(KnowledgeBaseMapper knowledgeBaseMapper,
                                KnowledgeDocumentMapper knowledgeDocumentMapper,
                                KnowledgeChunkMapper knowledgeChunkMapper,
                                KnowledgeImportTaskMapper knowledgeImportTaskMapper,
                                TextEmbeddingService textEmbeddingService,
                                KnowledgeVectorStore knowledgeVectorStore,
                                DocumentParsingService documentParsingService,
                                RagRetrievalScorer ragRetrievalScorer,
                                ThreadPoolTaskExecutor ragImportTaskExecutor) {
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.knowledgeDocumentMapper = knowledgeDocumentMapper;
        this.knowledgeChunkMapper = knowledgeChunkMapper;
        this.knowledgeImportTaskMapper = knowledgeImportTaskMapper;
        this.textEmbeddingService = textEmbeddingService;
        this.knowledgeVectorStore = knowledgeVectorStore;
        this.documentParsingService = documentParsingService;
        this.ragRetrievalScorer = ragRetrievalScorer;
        this.ragImportTaskExecutor = ragImportTaskExecutor;
        this.hybridRetriever = null;
        this.bm25Scorer = null;
        this.rerankerFactory = null;
        this.knowledgeGraphService = null;
        this.userRetrievalProfileService = null;
    }

    public List<KnowledgeBaseResponse> listKnowledgeBases(Long userId, boolean admin) {
        LambdaQueryWrapper<KnowledgeBase> wrapper = new LambdaQueryWrapper<>();
        if (!admin) {
            wrapper.and(owner -> owner
                    .eq(KnowledgeBase::getOwnerId, userId)
                    .or()
                    .isNull(KnowledgeBase::getOwnerId));
        }
        wrapper.orderByDesc(KnowledgeBase::getUpdatedAt);
        List<KnowledgeBase> list = knowledgeBaseMapper.selectList(wrapper);
        if (list.isEmpty()) {
            return List.of();
        }

        List<Long> kbIds = list.stream().map(KnowledgeBase::getId).toList();

        // 批量查询文档数
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<KnowledgeDocument> docWrapper = new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        docWrapper.select("knowledge_base_id", "count(*) as count")
                .in("knowledge_base_id", kbIds)
                .groupBy("knowledge_base_id");
        List<Map<String, Object>> docMaps = knowledgeDocumentMapper.selectMaps(docWrapper);
        Map<Long, Long> docCounts = docMaps.stream().collect(Collectors.toMap(
                m -> {
                    Object val = m.get("knowledge_base_id");
                    if (val == null) {
                        val = m.get("KNOWLEDGE_BASE_ID");
                    }
                    return ((Number) val).longValue();
                },
                m -> {
                    Object val = m.get("count");
                    if (val == null) {
                        val = m.get("COUNT");
                    }
                    return ((Number) val).longValue();
                },
                (a, b) -> a
        ));

        // 批量查询片段数
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<KnowledgeChunk> chunkWrapper = new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        chunkWrapper.select("knowledge_base_id", "count(*) as count")
                .in("knowledge_base_id", kbIds)
                .groupBy("knowledge_base_id");
        List<Map<String, Object>> chunkMaps = knowledgeChunkMapper.selectMaps(chunkWrapper);
        Map<Long, Long> chunkCounts = chunkMaps.stream().collect(Collectors.toMap(
                m -> {
                    Object val = m.get("knowledge_base_id");
                    if (val == null) {
                        val = m.get("KNOWLEDGE_BASE_ID");
                    }
                    return ((Number) val).longValue();
                },
                m -> {
                    Object val = m.get("count");
                    if (val == null) {
                        val = m.get("COUNT");
                    }
                    return ((Number) val).longValue();
                },
                (a, b) -> a
        ));

        return list.stream()
                .map(kb -> new KnowledgeBaseResponse(
                        kb.getId(),
                        kb.getName(),
                        kb.getDescription(),
                        kb.getOwnerId(),
                        docCounts.getOrDefault(kb.getId(), 0L),
                        chunkCounts.getOrDefault(kb.getId(), 0L),
                        kb.getCreatedAt(),
                        kb.getUpdatedAt()
                ))
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
        if (knowledgeBase == null || isDeleted(knowledgeBase)) {
            throw new IllegalArgumentException("知识库不存在");
        }
        if (!admin && knowledgeBase.getOwnerId() != null && !knowledgeBase.getOwnerId().equals(userId)) {
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
        getWritableKnowledgeBase(knowledgeBaseId, userId, admin);
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
        getWritableKnowledgeBase(knowledgeBaseId, userId, admin);
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
        getWritableKnowledgeBase(knowledgeBaseId, userId, admin);
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
        getWritableKnowledgeBase(knowledgeBaseId, userId, admin);
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
        List<VectorSearchHit> vectorHits = searchVectorCandidates(knowledgeBaseId, query, candidateLimit);
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
            List<GraphEvidence> graphEvidence = findGraphEvidence(knowledgeBaseId, query, List.of(), candidateLimit);
            loadGraphFallbackCandidates(knowledgeBaseId, graphEvidence, candidates);
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

        List<RetrievalCandidate> rankedCandidates = rankCandidates(
                query, candidates, minScore, safeTopK);

        attachGraphEvidence(knowledgeBaseId, query, rankedCandidates, candidateLimit);
        enrichRetrievedCandidates(rankedCandidates, query, safeContextWindow, safeContextMaxChars);
        return rankedCandidates.stream()
                .map(this::toRetrievedChunk)
                .toList();
    }

    private List<VectorSearchHit> searchVectorCandidates(Long knowledgeBaseId, String query, int candidateLimit) {
        try {
            List<Double> queryEmbedding = textEmbeddingService.embed(query);
            // Recall stage intentionally uses a zero threshold: fusion/rerank stage applies minScore.
            // Otherwise keyword-only hits (vectorScore=0) would be dropped before they can be rescued.
            return knowledgeVectorStore.search(knowledgeBaseId, queryEmbedding, candidateLimit, VECTOR_RECALL_SCORE_THRESHOLD);
        } catch (IllegalStateException e) {
            return List.of();
        }
    }

    private List<GraphEvidence> findGraphEvidence(Long knowledgeBaseId,
                                                  String query,
                                                  Collection<Long> candidateChunkIds,
                                                  int candidateLimit) {
        if (knowledgeGraphService == null) {
            return List.of();
        }
        try {
            return knowledgeGraphService.findEvidence(
                    knowledgeBaseId,
                    query,
                    candidateChunkIds,
                    Math.max(6, Math.min(18, candidateLimit))
            );
        } catch (Exception e) {
            log.warn("Knowledge graph evidence lookup failed: {}", e.getMessage());
            return List.of();
        }
    }

    private void loadGraphFallbackCandidates(Long knowledgeBaseId,
                                             List<GraphEvidence> graphEvidence,
                                             Map<Long, RetrievalCandidate> candidates) {
        if (graphEvidence == null || graphEvidence.isEmpty()) {
            return;
        }
        Set<Long> chunkIds = graphEvidence.stream()
                .map(GraphEvidence::chunkId)
                .filter(id -> id != null && id > 0)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (chunkIds.isEmpty()) {
            return;
        }
        Map<Long, List<String>> evidenceByChunkId = graphEvidence.stream()
                .filter(evidence -> evidence.chunkId() != null)
                .collect(Collectors.groupingBy(
                        GraphEvidence::chunkId,
                        LinkedHashMap::new,
                        Collectors.mapping(GraphEvidence::toContextLine, Collectors.toList())
                ));
        knowledgeChunkMapper.selectBatchIds(chunkIds).stream()
                .filter(chunk -> knowledgeBaseId.equals(chunk.getKnowledgeBaseId()))
                .filter(this::isCompatibleEmbedding)
                .forEach(chunk -> candidates.computeIfAbsent(chunk.getId(), RetrievalCandidate::new)
                        .chunk(chunk)
                        .keywordScore(0.20d)
                        .graphEvidence(evidenceByChunkId.getOrDefault(chunk.getId(), List.of())));
    }

    private void attachGraphEvidence(Long knowledgeBaseId,
                                     String query,
                                     List<RetrievalCandidate> rankedCandidates,
                                     int candidateLimit) {
        if (rankedCandidates == null || rankedCandidates.isEmpty()) {
            return;
        }
        Set<Long> candidateChunkIds = rankedCandidates.stream()
                .map(RetrievalCandidate::chunkId)
                .filter(id -> id != null && id > 0)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<GraphEvidence> graphEvidence = findGraphEvidence(knowledgeBaseId, query, candidateChunkIds, candidateLimit);
        if (graphEvidence.isEmpty()) {
            return;
        }

        Map<Long, RetrievalCandidate> byChunkId = rankedCandidates.stream()
                .collect(Collectors.toMap(
                        RetrievalCandidate::chunkId,
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        List<String> unmatched = new ArrayList<>();
        for (GraphEvidence evidence : graphEvidence) {
            String line = evidence.toContextLine();
            RetrievalCandidate candidate = byChunkId.get(evidence.chunkId());
            if (candidate == null) {
                unmatched.add(line);
                continue;
            }
            List<String> merged = new ArrayList<>(candidate.graphEvidence());
            if (!merged.contains(line)) {
                merged.add(line);
            }
            candidate.graphEvidence(merged);
        }
        if (!unmatched.isEmpty()) {
            RetrievalCandidate topCandidate = rankedCandidates.get(0);
            List<String> merged = new ArrayList<>(topCandidate.graphEvidence());
            for (String line : unmatched) {
                if (!merged.contains(line)) {
                    merged.add(line);
                }
            }
            topCandidate.graphEvidence(merged);
        }
    }

    /**
     * Rank retrieval candidates using the hybrid retrieval pipeline when available:
     * <ol>
     *   <li>Fuse dense (vector) and sparse (BM25) ranks via Reciprocal Rank Fusion (RRF).</li>
     *   <li>Re-score the fused candidates with the configured reranker; high-confidence hits can
     *       skip model rerank and use local score fusion directly.</li>
     *   <li>Apply minScore as a post-fusion / post-rerank threshold, then truncate to topK.</li>
     * </ol>
     * Falls back to the legacy linear fusion (0.65·vector + 0.35·keyword) when the RRF/rerank
     * components are absent, preserving the pre-industrial behavior for unit tests.
     */
    private List<RetrievalCandidate> rankCandidates(String query,
                                                    Map<Long, RetrievalCandidate> candidates,
                                                    double minScore,
                                                    int topK) {
        List<RetrievalCandidate> materialized = candidates.values().stream()
                .filter(candidate -> candidate.chunk() != null)
                .peek(candidate -> {
                    if (candidate.keywordScore() == null) {
                        candidate.keywordScore(ragRetrievalScorer.keywordScore(query, candidate.chunk()));
                    }
                })
                .toList();

        if (materialized.isEmpty()) {
            return List.of();
        }

        boolean canUseIndustrialPipeline = hybridRetriever != null
                && rerankerFactory != null
                && rerankerFactory.getReranker().isAvailable();

        if (canUseIndustrialPipeline) {
            return rankWithRrfAndRerank(query, materialized, minScore, topK);
        }
        return rankWithLegacyLinearFusion(query, materialized, minScore, topK);
    }

    /**
     * Hybrid ranking: RRF fusion -> configured rerank -> threshold -> topK.
     */
    private List<RetrievalCandidate> rankWithRrfAndRerank(String query,
                                                          List<RetrievalCandidate> materialized,
                                                          double minScore,
                                                          int topK) {
        // 1. Build dense / sparse rank inputs from the materialized candidates.
        //    Dense ranks by vector score; sparse ranks by BM25 (when available) or keyword score.
        Map<Long, Double> denseScores = new LinkedHashMap<>();
        Map<Long, Double> sparseScores = new LinkedHashMap<>();
        boolean useBm25 = bm25Scorer != null;
        Map<Long, Double> bm25Scores = useBm25
                ? bm25Scorer.scoreBatch(query, materialized.stream().map(RetrievalCandidate::chunk).toList())
                : Collections.emptyMap();

        for (RetrievalCandidate candidate : materialized) {
            denseScores.put(candidate.chunkId(),
                    candidate.vectorScore() == null ? 0.0 : candidate.vectorScore());
            double sparse = useBm25
                    ? bm25Scores.getOrDefault(candidate.chunkId(), 0.0)
                    : (candidate.keywordScore() == null ? 0.0 : candidate.keywordScore());
            sparseScores.put(candidate.chunkId(), sparse);
        }

        Map<Long, Double> denseSorted = sortByValueDescending(denseScores);
        Map<Long, Double> sparseSorted = sortByValueDescending(sparseScores);

        // 2. RRF fusion. We fuse over a generous candidate window so the reranker has room to work.
        int fusionWindow = Math.max(topK * 4, topK + 10);
        Map<Long, Double> fused = hybridRetriever.fuse(denseSorted, sparseSorted, fusionWindow);

        // Preserve fusion score on each candidate and order by it.
        List<RetrievalCandidate> fusedCandidates = new ArrayList<>();
        for (Map.Entry<Long, Double> entry : fused.entrySet()) {
            RetrievalCandidate candidate = candidatesById(materialized).get(entry.getKey());
            if (candidate == null) {
                continue;
            }
            candidate.rerankScore(entry.getValue());
            fusedCandidates.add(candidate);
        }

        // 3. Configured rerank (overwrites rerankScore with the reranker's relevance score).
        try {
            Reranker reranker = rerankerFactory.getReranker();
            if (!fusedCandidates.isEmpty()) {
                fusedCandidates = reranker.rerank(query, fusedCandidates);
            }
        } catch (Exception e) {
            // Rerank is best-effort: keep RRF scores if the reranker blows up.
            log.warn("Reranker invocation failed, falling back to RRF scores: {}", e.getMessage());
        }

        // 4. Apply minScore threshold + truncate.
        return fusedCandidates.stream()
                .filter(candidate -> (candidate.rerankScore() == null ? 0.0 : candidate.rerankScore()) >= minScore)
                .sorted((left, right) -> Double.compare(
                        right.rerankScore() == null ? 0.0 : right.rerankScore(),
                        left.rerankScore() == null ? 0.0 : left.rerankScore()))
                .limit(topK)
                .toList();
    }

    /**
     * Legacy ranking path: linear fusion 0.65·vector + 0.35·keyword.
     * Retained as an explicit fallback when the industrial components are not wired
     * (e.g. in unit tests that construct the service without a Spring context).
     */
    private List<RetrievalCandidate> rankWithLegacyLinearFusion(String query,
                                                                List<RetrievalCandidate> materialized,
                                                                double minScore,
                                                                int topK) {
        return materialized.stream()
                .peek(candidate -> candidate.rerankScore(ragRetrievalScorer.rerankScore(
                        candidate.vectorScore() == null ? 0.0 : candidate.vectorScore(),
                        candidate.keywordScore() == null ? 0.0 : candidate.keywordScore()
                )))
                .filter(candidate -> candidate.rerankScore() >= minScore)
                .sorted((left, right) -> Double.compare(right.rerankScore(), left.rerankScore()))
                .limit(topK)
                .toList();
    }

    private Map<Long, RetrievalCandidate> candidatesById(List<RetrievalCandidate> candidates) {
        Map<Long, RetrievalCandidate> byId = new LinkedHashMap<>();
        for (RetrievalCandidate candidate : candidates) {
            byId.put(candidate.chunkId(), candidate);
        }
        return byId;
    }

    private Map<Long, Double> sortByValueDescending(Map<Long, Double> scores) {
        return scores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (a, b) -> a,
                        LinkedHashMap::new
                ));
    }

    public List<RetrievedChunk> retrieveAuthorized(Long knowledgeBaseId,
                                                   String query,
                                                   int topK,
                                                   double minScore,
                                                   Long userId,
                                                   boolean admin) {
        return retrieveAuthorized(
                knowledgeBaseId,
                query,
                topK,
                minScore,
                DEFAULT_CONTEXT_WINDOW,
                DEFAULT_CONTEXT_MAX_CHARS,
                userId,
                admin
        );
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
        RetrievalPersonalization personalization = userRetrievalProfileService == null
                ? new RetrievalPersonalization(userId, Map.of())
                : userRetrievalProfileService.buildProfile(userId, query);
        int safeTopK = Math.min(MAX_RETRIEVAL_TOP_K, Math.max(1, topK));
        int personalizedTopK = personalization.active()
                ? Math.min(MAX_RETRIEVAL_TOP_K, Math.max(safeTopK, safeTopK * 2))
                : safeTopK;

        List<RetrievedChunk> chunks = retrieve(
                knowledgeBaseId,
                query,
                personalizedTopK,
                minScore,
                contextWindow,
                contextMaxChars
        );
        List<RetrievedChunk> personalized = applyPersonalization(chunks, personalization, safeTopK);
        if (userRetrievalProfileService != null) {
            userRetrievalProfileService.recordInteraction(userId, query, personalized);
        }
        return personalized;
    }

    private List<RetrievedChunk> applyPersonalization(List<RetrievedChunk> chunks,
                                                      RetrievalPersonalization personalization,
                                                      int topK) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        int safeTopK = Math.min(MAX_RETRIEVAL_TOP_K, Math.max(1, topK));
        if (personalization == null || !personalization.active()) {
            return chunks.stream().limit(safeTopK).toList();
        }

        for (RetrievedChunk chunk : chunks) {
            RetrievalPersonalization.PersonalizationMatch match = personalization.match(personalizationText(chunk));
            double boost = match.score();
            chunk.setPersonalizationScore(boost);
            chunk.setPersonalizationReasons(match.reasons());
            if (boost > 0.0d) {
                chunk.setScore(safeScore(chunk.getScore()) + boost);
            }
        }

        List<RetrievedChunk> personalized = chunks.stream()
                .sorted((left, right) -> Double.compare(safeScore(right.getScore()), safeScore(left.getScore())))
                .limit(safeTopK)
                .toList();
        for (int i = 0; i < personalized.size(); i++) {
            personalized.get(i).setRank(i + 1);
        }
        return personalized;
    }

    private String personalizationText(RetrievedChunk chunk) {
        if (chunk == null) {
            return "";
        }
        return String.join("\n",
                nullToEmpty(chunk.getContent()),
                nullToEmpty(chunk.getContextContent()),
                nullToEmpty(chunk.getSourceName()),
                nullToEmpty(chunk.getSectionTitle()),
                chunk.getMatchedTerms() == null ? "" : String.join(" ", chunk.getMatchedTerms()),
                chunk.getGraphEvidence() == null ? "" : String.join(" ", chunk.getGraphEvidence())
        );
    }

    private double safeScore(Double score) {
        return score == null || !Double.isFinite(score) ? 0.0d : score;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    @Transactional
    public KnowledgeReindexResponse rebuildEmbeddings(Long knowledgeBaseId, Long userId, boolean admin) {
        getWritableKnowledgeBase(knowledgeBaseId, userId, admin);
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
        }
        Db.updateBatchById(chunks);
        knowledgeVectorStore.upsert(chunks);
        rebuildKnowledgeGraphForChunksBestEffort(knowledgeBaseId, chunks);

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
        getWritableKnowledgeBase(id, userId, admin);
        knowledgeVectorStore.deleteKnowledgeBase(id);
        if (knowledgeGraphService != null) {
            knowledgeGraphService.deleteKnowledgeBaseGraph(id);
        }
        knowledgeChunkMapper.delete(new LambdaQueryWrapper<KnowledgeChunk>().eq(KnowledgeChunk::getKnowledgeBaseId, id));
        knowledgeDocumentMapper.delete(new LambdaQueryWrapper<KnowledgeDocument>().eq(KnowledgeDocument::getKnowledgeBaseId, id));
        knowledgeImportTaskMapper.delete(new LambdaQueryWrapper<KnowledgeImportTask>().eq(KnowledgeImportTask::getKnowledgeBaseId, id));
        knowledgeBaseMapper.deleteById(id);
    }

    private KnowledgeBase getWritableKnowledgeBase(Long id, Long userId, boolean admin) {
        KnowledgeBase knowledgeBase = getAuthorizedKnowledgeBase(id, userId, admin);
        if (!admin && knowledgeBase.getOwnerId() == null) {
            throw new ForbiddenException("无权修改该知识库");
        }
        return knowledgeBase;
    }

    private boolean isDeleted(KnowledgeBase knowledgeBase) {
        return Integer.valueOf(1).equals(knowledgeBase.getDeleted());
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
        if (knowledgeGraphService != null) {
            knowledgeGraphService.deleteDocumentGraph(task.getDocumentId());
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
                batchChunks.add(chunk);
            }
            Db.saveBatch(batchChunks);
            knowledgeVectorStore.upsert(batchChunks);
            insertedChunks.addAll(batchChunks);
            processed += batchChunks.size();
            int progress = 30 + (int) Math.round(65.0 * processed / chunks.size());
            progressReporter.update("向量化、入库与索引写入中", Math.min(progress, 95), chunks.size(), processed);
        }
        progressReporter.update("导入完成", 100, chunks.size(), insertedChunks.size());

        rebuildDocumentGraphBestEffort(knowledgeBaseId, document.getId(), insertedChunks);
        return toDocumentResponse(document);
    }

    private void rebuildDocumentGraphBestEffort(Long knowledgeBaseId,
                                                Long documentId,
                                                List<KnowledgeChunk> insertedChunks) {
        if (knowledgeGraphService == null) {
            return;
        }
        try {
            knowledgeGraphService.rebuildDocumentGraph(knowledgeBaseId, documentId, insertedChunks);
        } catch (Exception e) {
            log.warn("Knowledge graph rebuild failed for document {}: {}", documentId, e.getMessage());
        }
    }

    private void rebuildKnowledgeGraphForChunksBestEffort(Long knowledgeBaseId, List<KnowledgeChunk> chunks) {
        if (knowledgeGraphService == null || chunks == null || chunks.isEmpty()) {
            return;
        }
        Map<Long, List<KnowledgeChunk>> chunksByDocument = chunks.stream()
                .filter(chunk -> chunk.getDocumentId() != null)
                .collect(Collectors.groupingBy(
                        KnowledgeChunk::getDocumentId,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));
        for (Map.Entry<Long, List<KnowledgeChunk>> entry : chunksByDocument.entrySet()) {
            rebuildDocumentGraphBestEffort(knowledgeBaseId, entry.getKey(), entry.getValue());
        }
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

        List<KnowledgeChunk> prefiltered = searchKeywordPrefilterCandidates(knowledgeBaseId, terms, limit);
        if (prefiltered.isEmpty() || bm25Scorer == null) {
            return prefiltered.stream()
                    .limit(Math.max(1, limit))
                    .toList();
        }

        List<KnowledgeChunk> compatible = prefiltered.stream()
                .filter(this::isCompatibleEmbedding)
                .toList();
        if (compatible.isEmpty()) {
            return List.of();
        }

        Map<Long, Double> scores = bm25Scorer.scoreBatch(query, compatible);
        if (scores.isEmpty()) {
            return compatible.stream()
                    .limit(Math.max(1, limit))
                    .toList();
        }

        return compatible.stream()
                .filter(chunk -> scores.containsKey(chunk.getId()))
                .sorted((a, b) -> Double.compare(
                        scores.getOrDefault(b.getId(), 0.0),
                        scores.getOrDefault(a.getId(), 0.0)))
                .limit(Math.max(1, limit))
                .toList();
    }

    private List<KnowledgeChunk> searchKeywordPrefilterCandidates(Long knowledgeBaseId, List<String> terms, int limit) {
        String fulltextQuery = buildBooleanFulltextQuery(terms);
        if (!StringUtils.hasText(fulltextQuery)) {
            return List.of();
        }
        int prefilterLimit = Math.min(1000, Math.max(Math.max(1, limit) * 8, 200));
        LambdaQueryWrapper<KnowledgeChunk> wrapper = new LambdaQueryWrapper<KnowledgeChunk>()
                .eq(KnowledgeChunk::getKnowledgeBaseId, knowledgeBaseId)
                .eq(KnowledgeChunk::getEmbeddingProvider, textEmbeddingService.provider())
                .eq(KnowledgeChunk::getEmbeddingModel, textEmbeddingService.model())
                .eq(KnowledgeChunk::getEmbeddingDimension, textEmbeddingService.dimensions())
                .apply("MATCH(content, source_name, section_title) AGAINST({0} IN BOOLEAN MODE)", fulltextQuery)
                .last("LIMIT " + prefilterLimit);
        try {
            return knowledgeChunkMapper.selectList(wrapper);
        } catch (Exception e) {
            log.warn("FULLTEXT prefilter failed, falling back to bounded chunk scan: {}", e.getMessage());
            return knowledgeChunkMapper.selectList(new LambdaQueryWrapper<KnowledgeChunk>()
                    .eq(KnowledgeChunk::getKnowledgeBaseId, knowledgeBaseId)
                    .eq(KnowledgeChunk::getEmbeddingProvider, textEmbeddingService.provider())
                    .eq(KnowledgeChunk::getEmbeddingModel, textEmbeddingService.model())
                    .eq(KnowledgeChunk::getEmbeddingDimension, textEmbeddingService.dimensions())
                    .last("LIMIT " + prefilterLimit));
        }
    }

    private String buildBooleanFulltextQuery(List<String> terms) {
        return terms.stream()
                .filter(StringUtils::hasText)
                .map(term -> term.replaceAll("[+\\-~*()<>@\"']", " ").trim())
                .filter(StringUtils::hasText)
                .collect(Collectors.joining(" "));
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
                candidate.graphEvidence(),
                candidate.contextContent(),
                candidate.contextChunkIndexes(),
                candidate.personalizationScore(),
                candidate.personalizationReasons()
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
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int tokenCount = 0;
        int length = text.length();
        for (int i = 0; i < length; ) {
            int codePoint = text.codePointAt(i);
            if (isCjk(codePoint)) {
                tokenCount++;
                i += Character.charCount(codePoint);
            } else {
                // Skip whitespace, then count a word
                while (i < length && Character.isWhitespace(text.codePointAt(i))) {
                    i++;
                }
                if (i < length) {
                    tokenCount++;
                    while (i < length && !Character.isWhitespace(text.codePointAt(i))) {
                        i++;
                    }
                }
            }
        }
        return Math.max(1, tokenCount);
    }

    private boolean isCjk(int codePoint) {
        return (codePoint >= 0x4E00 && codePoint <= 0x9FFF)
                || (codePoint >= 0x3400 && codePoint <= 0x4DBF)
                || (codePoint >= 0x20000 && codePoint <= 0x2A6DF)
                || (codePoint >= 0x2A700 && codePoint <= 0x2B73F)
                || (codePoint >= 0x2B740 && codePoint <= 0x2B81F)
                || (codePoint >= 0x2B820 && codePoint <= 0x2CEAF)
                || (codePoint >= 0x2F800 && codePoint <= 0x2FA1F)
                || (codePoint >= 0x3000 && codePoint <= 0x303F)
                || (codePoint >= 0x3040 && codePoint <= 0x309F)
                || (codePoint >= 0x30A0 && codePoint <= 0x30FF)
                || (codePoint >= 0xAC00 && codePoint <= 0xD7AF)
                || (codePoint >= 0xF900 && codePoint <= 0xFAFF)
                || (codePoint >= 0xFF00 && codePoint <= 0xFFEF);
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

}
