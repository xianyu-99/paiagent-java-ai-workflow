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
    private List<String> graphEvidence = List.of();
    private String contextContent = "";
    private List<Integer> contextChunkIndexes = List.of();
    private Double personalizationScore = 0.0d;
    private List<String> personalizationReasons = List.of();

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

    public List<String> graphEvidence() {
        return graphEvidence;
    }

    public RetrievalCandidate graphEvidence(List<String> graphEvidence) {
        this.graphEvidence = graphEvidence == null ? List.of() : graphEvidence;
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

    public Double personalizationScore() {
        return personalizationScore;
    }

    public RetrievalCandidate personalizationScore(Double personalizationScore) {
        this.personalizationScore = personalizationScore == null ? 0.0d : personalizationScore;
        return this;
    }

    public List<String> personalizationReasons() {
        return personalizationReasons;
    }

    public RetrievalCandidate personalizationReasons(List<String> personalizationReasons) {
        this.personalizationReasons = personalizationReasons == null ? List.of() : personalizationReasons;
        return this;
    }
}
