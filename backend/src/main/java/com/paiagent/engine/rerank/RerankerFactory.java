package com.paiagent.engine.rerank;

import com.paiagent.service.rag.RetrievalCandidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Factory that creates the best available Reranker.
 * <p>
 * Priority:
 * <ol>
 *   <li>Local HTTP Cross-Encoder reranker when enabled</li>
 *   <li>DashScopeReranker when external rerank is enabled</li>
 *   <li>LLMReranker fallback when explicitly enabled</li>
 *   <li>LocalScoreReranker when model rerank is unavailable</li>
 * </ol>
 */
@Component
public class RerankerFactory {

    private static final Logger log = LoggerFactory.getLogger(RerankerFactory.class);

    private final Reranker reranker;

    public RerankerFactory(RerankerProperties properties) {
        if (!properties.isEnabled()) {
            log.warn("RerankerFactory: rerank disabled, using pass-through ranking");
            this.reranker = disabledReranker();
            return;
        }

        if (!properties.isExternalEnabled()) {
            log.info("RerankerFactory: using LocalScoreReranker (no external model call)");
            this.reranker = new LocalScoreReranker();
            return;
        }

        if (properties.isLocalEnabled() && StringUtils.hasText(properties.getLocalBaseUrl())) {
            LocalHttpReranker localHttpReranker = new LocalHttpReranker(properties);
            if (localHttpReranker.isAvailable()) {
                log.info("RerankerFactory: using LocalHttpReranker ({})", properties.getLocalBaseUrl());
                this.reranker = new OptimizedReranker(localHttpReranker, properties);
                return;
            }
        }

        DashScopeReranker dashScopeReranker = new DashScopeReranker(properties);
        if (dashScopeReranker.isAvailable()) {
            log.info("RerankerFactory: using DashScopeReranker (model={})", properties.getModel());
            this.reranker = new OptimizedReranker(dashScopeReranker, properties);
            return;
        }

        LLMReranker llmReranker = new LLMReranker(properties);
        if (llmReranker.isAvailable()) {
            log.info("RerankerFactory: using LLMReranker fallback (qwen-turbo via DashScope compatible API)");
            this.reranker = new OptimizedReranker(llmReranker, properties);
            return;
        }

        log.warn("RerankerFactory: no external reranker available, falling back to LocalScoreReranker");
        this.reranker = new LocalScoreReranker();
    }

    private Reranker disabledReranker() {
        return new Reranker() {
            @Override
            public List<RetrievalCandidate> rerank(String query, List<RetrievalCandidate> candidates) {
                return candidates;
            }

            @Override
            public boolean isAvailable() {
                return false;
            }
        };
    }

    public Reranker getReranker() {
        return reranker;
    }

    private static final class OptimizedReranker implements Reranker {

        private final Reranker delegate;
        private final int candidateLimit;
        private final boolean cacheEnabled;
        private final long cacheTtlMillis;
        private final int cacheMaxSize;
        private final boolean highConfidenceSkipEnabled;
        private final double highConfidenceMinVectorScore;
        private final double highConfidenceMinKeywordScore;
        private final double highConfidenceMinRrfMargin;
        private final Reranker fallback = new LocalScoreReranker();
        private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

        private OptimizedReranker(Reranker delegate, RerankerProperties properties) {
            this.delegate = delegate;
            this.candidateLimit = Math.max(1, properties.getCandidateLimit());
            this.cacheEnabled = properties.isCacheEnabled();
            this.cacheTtlMillis = Math.max(1, properties.getCacheTtlSeconds()) * 1000L;
            this.cacheMaxSize = Math.max(1, properties.getCacheMaxSize());
            this.highConfidenceSkipEnabled = properties.isHighConfidenceSkipEnabled();
            this.highConfidenceMinVectorScore = Math.max(0.0, properties.getHighConfidenceMinVectorScore());
            this.highConfidenceMinKeywordScore = Math.max(0.0, properties.getHighConfidenceMinKeywordScore());
            this.highConfidenceMinRrfMargin = Math.max(0.0, properties.getHighConfidenceMinRrfMargin());
        }

        @Override
        public List<RetrievalCandidate> rerank(String query, List<RetrievalCandidate> candidates) {
            if (candidates == null || candidates.isEmpty()) {
                return candidates == null ? List.of() : candidates;
            }
            if (!delegate.isAvailable()) {
                return fallback.rerank(query, candidates);
            }

            int limit = Math.min(candidateLimit, candidates.size());
            List<RetrievalCandidate> head = new ArrayList<>(candidates.subList(0, limit));
            List<RetrievalCandidate> tail = candidates.size() > limit
                    ? new ArrayList<>(candidates.subList(limit, candidates.size()))
                    : List.of();

            if (highConfidenceSkipEnabled && isHighConfidenceHit(head)) {
                return merge(fallback.rerank(query, head), tail);
            }

            String cacheKey = cacheEnabled ? cacheKey(query, head) : null;
            if (cacheEnabled) {
                CacheEntry cached = getCached(cacheKey);
                if (cached != null) {
                    return merge(applyCachedScores(head, cached), tail);
                }
            }

            List<RetrievalCandidate> rerankedHead = delegate.rerank(query, head);
            if (rerankedHead == null || rerankedHead.isEmpty()) {
                rerankedHead = head;
            }
            if (cacheEnabled) {
                putCached(cacheKey, rerankedHead);
            }
            return merge(rerankedHead, tail);
        }

        @Override
        public boolean isAvailable() {
            return delegate.isAvailable();
        }

        private boolean isHighConfidenceHit(List<RetrievalCandidate> candidates) {
            if (candidates == null || candidates.isEmpty()) {
                return false;
            }
            RetrievalCandidate first = candidates.get(0);
            boolean scoreEnough = safe(first.vectorScore()) >= highConfidenceMinVectorScore
                    && safe(first.keywordScore()) >= highConfidenceMinKeywordScore;
            if (!scoreEnough) {
                return false;
            }
            if (highConfidenceMinRrfMargin <= 0.0 || candidates.size() == 1) {
                return true;
            }
            double secondScore = safe(candidates.get(1).rerankScore());
            return safe(first.rerankScore()) - secondScore >= highConfidenceMinRrfMargin;
        }

        private double safe(Double score) {
            return score == null ? 0.0 : score;
        }

        private List<RetrievalCandidate> applyCachedScores(List<RetrievalCandidate> candidates, CacheEntry entry) {
            Map<Long, RetrievalCandidate> byId = candidates.stream()
                    .collect(Collectors.toMap(
                            RetrievalCandidate::chunkId,
                            Function.identity(),
                            (left, right) -> left,
                            LinkedHashMap::new
                    ));

            List<RetrievalCandidate> ordered = new ArrayList<>();
            for (Long chunkId : entry.rankedChunkIds()) {
                RetrievalCandidate candidate = byId.remove(chunkId);
                if (candidate == null) {
                    continue;
                }
                Double score = entry.scoresByChunkId().get(chunkId);
                if (score != null) {
                    candidate.rerankScore(score);
                }
                ordered.add(candidate);
            }
            ordered.addAll(byId.values());
            updateRanks(ordered);
            return ordered;
        }

        private List<RetrievalCandidate> merge(List<RetrievalCandidate> rerankedHead, List<RetrievalCandidate> tail) {
            Map<Long, RetrievalCandidate> merged = new LinkedHashMap<>();
            for (RetrievalCandidate candidate : rerankedHead) {
                merged.put(candidate.chunkId(), candidate);
            }
            for (RetrievalCandidate candidate : tail) {
                merged.putIfAbsent(candidate.chunkId(), candidate);
            }
            List<RetrievalCandidate> result = new ArrayList<>(merged.values());
            updateRanks(result);
            return result;
        }

        private void updateRanks(List<RetrievalCandidate> candidates) {
            for (int i = 0; i < candidates.size(); i++) {
                candidates.get(i).rank(i + 1);
            }
        }

        private CacheEntry getCached(String key) {
            CacheEntry entry = cache.get(key);
            if (entry == null) {
                return null;
            }
            if (entry.expiresAtMillis() < System.currentTimeMillis()) {
                cache.remove(key);
                return null;
            }
            return entry;
        }

        private void putCached(String key, List<RetrievalCandidate> candidates) {
            if (cache.size() >= cacheMaxSize) {
                evictOne();
            }
            Map<Long, Double> scores = new HashMap<>();
            List<Long> rankedIds = new ArrayList<>();
            for (RetrievalCandidate candidate : candidates) {
                rankedIds.add(candidate.chunkId());
                scores.put(candidate.chunkId(), candidate.rerankScore() == null ? 0.0 : candidate.rerankScore());
            }
            cache.put(key, new CacheEntry(
                    List.copyOf(rankedIds),
                    Map.copyOf(scores),
                    System.currentTimeMillis() + cacheTtlMillis
            ));
        }

        private void evictOne() {
            cache.entrySet().stream()
                    .min(Comparator.comparingLong(entry -> entry.getValue().expiresAtMillis()))
                    .map(Map.Entry::getKey)
                    .ifPresent(cache::remove);
        }

        private String cacheKey(String query, List<RetrievalCandidate> candidates) {
            String raw = (query == null ? "" : query.trim().toLowerCase()) + "|" + candidates.stream()
                    .map(candidate -> String.valueOf(candidate.chunkId()))
                    .collect(Collectors.joining(","));
            return sha256(raw);
        }

        private String sha256(String raw) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
                StringBuilder hex = new StringBuilder(hash.length * 2);
                for (byte value : hash) {
                    hex.append(String.format("%02x", value));
                }
                return hex.toString();
            } catch (NoSuchAlgorithmException e) {
                return raw;
            }
        }
    }

    private record CacheEntry(
            List<Long> rankedChunkIds,
            Map<Long, Double> scoresByChunkId,
            long expiresAtMillis
    ) {
    }
}
