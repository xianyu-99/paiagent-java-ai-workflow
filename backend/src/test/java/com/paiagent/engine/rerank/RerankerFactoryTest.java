package com.paiagent.engine.rerank;

import com.paiagent.service.rag.RetrievalCandidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RerankerFactoryTest {

    @Test
    @DisplayName("should return no-op reranker when disabled")
    void disabledReturnsNoOp() {
        RerankerProperties props = new RerankerProperties();
        props.setEnabled(false);
        props.setLlmFallbackEnabled(false);

        RerankerFactory factory = new RerankerFactory(props);
        Reranker reranker = factory.getReranker();

        assertNotNull(reranker);
        List<RetrievalCandidate> candidates = new ArrayList<>();
        RetrievalCandidate c = new RetrievalCandidate(1L);
        c.rerankScore(0.5);
        candidates.add(c);
        List<RetrievalCandidate> result = reranker.rerank("test", candidates);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).rerankScore()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("should handle empty candidate list")
    void emptyCandidates() {
        RerankerProperties props = new RerankerProperties();
        props.setEnabled(false);
        RerankerFactory factory = new RerankerFactory(props);
        Reranker reranker = factory.getReranker();

        List<RetrievalCandidate> result = reranker.rerank("test", List.of());
        assertThat(result).isEmpty();
    }
}
