package com.paiagent.engine.rerank;

import com.paiagent.service.rag.RetrievalCandidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Factory that creates the best available Reranker.
 * <p>
 * Priority:
 * <ol>
 *   <li>DashScopeReranker (Alibaba Bailian Rerank API)</li>
 *   <li>LLMReranker (DashScope LLM-based fallback)</li>
 *   <li>NoOp (pass-through)</li>
 * </ol>
 */
@Component
public class RerankerFactory {

    private static final Logger log = LoggerFactory.getLogger(RerankerFactory.class);

    private final Reranker reranker;

    public RerankerFactory(RerankerProperties properties) {
        // Try LLMReranker first (qwen-turbo + DashScope compatible API — most reliable)
        LLMReranker llmReranker = new LLMReranker(properties);
        if (llmReranker.isAvailable()) {
            log.info("RerankerFactory: using LLMReranker (qwen-turbo via DashScope compatible API)");
            this.reranker = llmReranker;
            return;
        }

        // Fall back to dedicated DashScope Rerank API (requires gte-rerank model access)
        DashScopeReranker dashScopeReranker = new DashScopeReranker(properties);
        if (dashScopeReranker.isAvailable()) {
            log.info("RerankerFactory: using DashScopeReranker (model={})", properties.getModel());
            this.reranker = dashScopeReranker;
            return;
        }

        log.warn("RerankerFactory: no reranker available, using no-op pass-through");
        this.reranker = new Reranker() {
            @Override
            public List<RetrievalCandidate> rerank(String query, List<RetrievalCandidate> candidates) {
                return candidates;
            }
        };
    }

    public Reranker getReranker() {
        return reranker;
    }
}
