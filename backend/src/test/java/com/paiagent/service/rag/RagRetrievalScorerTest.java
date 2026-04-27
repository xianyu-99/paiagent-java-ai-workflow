package com.paiagent.service.rag;

import com.paiagent.entity.KnowledgeChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void searchTermsIgnoreChineseQuestionFillers() {
        List<String> terms = scorer.searchTerms("知识库怎么导入");

        assertTrue(terms.contains("知识库"));
        assertTrue(terms.contains("导入"));
        assertFalse(terms.contains("怎么"));
        assertFalse(terms.stream().anyMatch(term -> term.contains("怎么")));
    }

    @Test
    void matchedTermsIncludeMetadataHits() {
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setContent("这里介绍向量检索流程");
        chunk.setSourceName("rag-guide.md");
        chunk.setSectionTitle("知识库管理");

        List<String> matchedTerms = scorer.matchedTerms("rag guide 知识库", chunk);

        assertTrue(matchedTerms.contains("rag"));
        assertTrue(matchedTerms.contains("guide"));
        assertTrue(matchedTerms.contains("知识库"));
    }
}
