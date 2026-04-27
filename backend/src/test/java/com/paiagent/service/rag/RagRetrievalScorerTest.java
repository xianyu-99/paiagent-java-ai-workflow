package com.paiagent.service.rag;

import com.paiagent.entity.KnowledgeChunk;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RagRetrievalScorerTest {

    private final RagRetrievalScorer scorer = new RagRetrievalScorer();

    @Test
    void keywordScoreMatchesChineseTermsAndMetadata() {
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setContent("PaiAgent 支持知识库导入、向量检索和工作流编排。");
        chunk.setSourceName("rag-guide.md");
        chunk.setSectionTitle("知识库管理");

        double score = scorer.keywordScore("知识库怎么导入", chunk);

        assertTrue(score > 0.2);
    }

    @Test
    void rerankCombinesVectorAndKeywordScores() {
        double hybridScore = scorer.rerankScore(0.7, 0.8);
        double vectorOnlyScore = scorer.rerankScore(0.7, 0.0);

        assertTrue(hybridScore > vectorOnlyScore);
    }
}
