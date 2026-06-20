package com.paiagent.engine.retrieval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hybrid retriever that fuses dense (vector) and sparse (BM25) retrieval results
 * using Reciprocal Rank Fusion (RRF).
 */
@Component
public class HybridRetriever {

    private static final Logger log = LoggerFactory.getLogger(HybridRetriever.class);

    private final int rrfK;

    private final double denseWeight;

    private final double sparseWeight;

    public HybridRetriever(
            @Value("${paiagent.rag.retrieval.hybrid.rrf-k:60}") int rrfK,
            @Value("${paiagent.rag.retrieval.hybrid.dense-weight:1.0}") double denseWeight,
            @Value("${paiagent.rag.retrieval.hybrid.sparse-weight:1.0}") double sparseWeight) {
        this.rrfK = rrfK;
        this.denseWeight = denseWeight;
        this.sparseWeight = sparseWeight;
    }

    /**
     * Fuse dense and sparse retrieval results using Reciprocal Rank Fusion.
     * <p>
     * RRF score for a document d:
     *   RRF(d) = Σ weight_i / (k + rank_i(d))
     * where rank_i(d) is the rank of d in result list i (1-based),
     * and k is a constant (default 60) that dampens the effect of high ranks.
     *
     * @param denseResults  chunkId → vector similarity score, pre-sorted descending
     * @param bm25Results   chunkId → BM25 score, pre-sorted descending
     * @param topK          maximum number of results to return
     * @return merged results: chunkId → RRF fusion score, sorted descending
     */
    public Map<Long, Double> fuse(Map<Long, Double> denseResults,
                                   Map<Long, Double> bm25Results,
                                   int topK) {
        if ((denseResults == null || denseResults.isEmpty())
                && (bm25Results == null || bm25Results.isEmpty())) {
            return new LinkedHashMap<>();
        }

        // Build rank maps (1-based)
        Map<Long, Integer> denseRanks = buildRankMap(denseResults);
        Map<Long, Integer> bm25Ranks = buildRankMap(bm25Results);

        // Collect all unique chunk IDs
        Map<Long, Double> rrfScores = new LinkedHashMap<>();
        if (denseRanks != null) {
            denseRanks.keySet().forEach(id -> rrfScores.putIfAbsent(id, 0.0));
        }
        if (bm25Ranks != null) {
            bm25Ranks.keySet().forEach(id -> rrfScores.putIfAbsent(id, 0.0));
        }

        // Compute RRF scores
        for (Long chunkId : rrfScores.keySet()) {
            double score = 0.0;

            if (denseRanks != null && denseRanks.containsKey(chunkId)) {
                score += denseWeight / (rrfK + denseRanks.get(chunkId));
            }
            if (bm25Ranks != null && bm25Ranks.containsKey(chunkId)) {
                score += sparseWeight / (rrfK + bm25Ranks.get(chunkId));
            }

            rrfScores.put(chunkId, score);
        }

        // Sort by RRF score descending and limit to topK
        List<Map.Entry<Long, Double>> sorted = new ArrayList<>(rrfScores.entrySet());
        sorted.sort(Map.Entry.<Long, Double>comparingByValue().reversed());

        Map<Long, Double> result = new LinkedHashMap<>();
        int limit = Math.min(topK, sorted.size());
        for (int i = 0; i < limit; i++) {
            Map.Entry<Long, Double> entry = sorted.get(i);
            result.put(entry.getKey(), entry.getValue());
        }

        log.debug("RRF fusion: dense={}, sparse={}, merged={}",
                denseResults != null ? denseResults.size() : 0,
                bm25Results != null ? bm25Results.size() : 0,
                result.size());

        return result;
    }

    private Map<Long, Integer> buildRankMap(Map<Long, Double> results) {
        if (results == null || results.isEmpty()) {
            return null;
        }
        Map<Long, Integer> ranks = new LinkedHashMap<>();
        int rank = 1;
        for (Long chunkId : results.keySet()) {
            ranks.put(chunkId, rank++);
        }
        return ranks;
    }
}
