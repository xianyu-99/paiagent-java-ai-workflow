package com.paiagent.engine.rerank;

import com.paiagent.service.rag.RetrievalCandidate;

import java.util.Comparator;
import java.util.List;

/**
 * Local reranker for the online retrieval path.
 * It avoids another model call by combining the existing vector, keyword and RRF scores.
 */
public class LocalScoreReranker implements Reranker {

    private static final double VECTOR_WEIGHT = 0.45;
    private static final double KEYWORD_WEIGHT = 0.35;
    private static final double RRF_WEIGHT = 0.20;

    @Override
    public List<RetrievalCandidate> rerank(String query, List<RetrievalCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return candidates == null ? List.of() : candidates;
        }

        double maxRrfScore = candidates.stream()
                .mapToDouble(candidate -> safe(candidate.rerankScore()))
                .max()
                .orElse(0.0);

        for (RetrievalCandidate candidate : candidates) {
            double vectorScore = clamp(candidate.vectorScore());
            double keywordScore = clamp(candidate.keywordScore());
            double rrfScore = maxRrfScore > 0.0 ? safe(candidate.rerankScore()) / maxRrfScore : 0.0;
            double finalScore = VECTOR_WEIGHT * vectorScore
                    + KEYWORD_WEIGHT * keywordScore
                    + RRF_WEIGHT * clamp(rrfScore);
            candidate.rerankScore(clamp(finalScore));
        }

        candidates.sort(Comparator.comparing(
                (RetrievalCandidate candidate) -> safe(candidate.rerankScore())).reversed());
        for (int i = 0; i < candidates.size(); i++) {
            candidates.get(i).rank(i + 1);
        }
        return candidates;
    }

    private double clamp(Double score) {
        return clamp(safe(score));
    }

    private double clamp(double score) {
        return Math.max(0.0, Math.min(1.0, score));
    }

    private double safe(Double score) {
        return score == null ? 0.0 : score;
    }
}
