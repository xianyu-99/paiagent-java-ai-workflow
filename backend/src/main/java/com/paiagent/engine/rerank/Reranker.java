package com.paiagent.engine.rerank;

import com.paiagent.service.rag.RetrievalCandidate;

import java.util.List;

/**
 * Re-scores retrieval candidates after the first-stage hybrid recall.
 * Implementations may be local score fusion or an external cross-encoder reranker.
 */
public interface Reranker {

    /**
     * Re-rank candidates against a query.
     * The returned list is sorted by relevance (highest first) and
     * each candidate's {@link RetrievalCandidate#rerankScore()} is updated.
     *
     * @param query      the user query
     * @param candidates candidates from the initial retrieval stage
     * @return re-ranked candidates sorted by relevance descending
     */
    List<RetrievalCandidate> rerank(String query, List<RetrievalCandidate> candidates);

    /**
     * Whether this reranker is available (configured and reachable).
     */
    default boolean isAvailable() {
        return true;
    }
}
