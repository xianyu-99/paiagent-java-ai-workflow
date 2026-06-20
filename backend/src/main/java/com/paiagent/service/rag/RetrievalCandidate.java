package com.paiagent.service.rag;

import com.paiagent.entity.KnowledgeChunk;

import java.util.List;

/**
 * A candidate chunk during RAG retrieval, holding vector score, keyword score,
 * and the final rerank score assigned by a {@link com.paiagent.engine.rerank.Reranker}.
 */
public class RetrievalCandidate {

    private final Long chunkId;
    private KnowledgeChunk chunk;
    private Double vectorScore;
    private Double keywordScore;
    private Double rerankScore = 0.0;
    private Integer rank = 0;
    private List<String> matchedTerms = List.of();
    private String contextContent = "";
    private List<Integer> contextChunkIndexes = List.of();

    public RetrievalCandidate(Long chunkId) {
        this.chunkId = chunkId;
    }

    public Long chunkId() {
        return chunkId;
    }

    public KnowledgeChunk chunk() {
        return chunk;
    }

    public RetrievalCandidate chunk(KnowledgeChunk chunk) {
        this.chunk = chunk;
        return this;
    }

    public Double vectorScore() {
        return vectorScore;
    }

    public RetrievalCandidate vectorScore(Double vectorScore) {
        this.vectorScore = vectorScore;
        return this;
    }

    public Double keywordScore() {
        return keywordScore;
    }

    public RetrievalCandidate keywordScore(Double keywordScore) {
        this.keywordScore = keywordScore;
        return this;
    }

    public Double rerankScore() {
        return rerankScore;
    }

    public RetrievalCandidate rerankScore(Double rerankScore) {
        this.rerankScore = rerankScore;
        return this;
    }

    public Integer rank() {
        return rank;
    }

    public RetrievalCandidate rank(Integer rank) {
        this.rank = rank;
        return this;
    }

    public List<String> matchedTerms() {
        return matchedTerms;
    }

    public RetrievalCandidate matchedTerms(List<String> matchedTerms) {
        this.matchedTerms = matchedTerms == null ? List.of() : matchedTerms;
        return this;
    }

    public String contextContent() {
        return contextContent;
    }

    public RetrievalCandidate contextContent(String contextContent) {
        this.contextContent = contextContent == null ? "" : contextContent;
        return this;
    }

    public List<Integer> contextChunkIndexes() {
        return contextChunkIndexes;
    }

    public RetrievalCandidate contextChunkIndexes(List<Integer> contextChunkIndexes) {
        this.contextChunkIndexes = contextChunkIndexes == null ? List.of() : contextChunkIndexes;
        return this;
    }
}
