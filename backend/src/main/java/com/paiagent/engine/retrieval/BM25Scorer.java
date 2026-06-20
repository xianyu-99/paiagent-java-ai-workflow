package com.paiagent.engine.retrieval;

import com.paiagent.entity.KnowledgeChunk;
import com.paiagent.engine.tokenizer.ChineseTokenizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Standard BM25 scorer for sparse retrieval.
 * Stateless: document statistics are computed per batch from the provided candidates.
 */
@Component
public class BM25Scorer {

    private static final Logger log = LoggerFactory.getLogger(BM25Scorer.class);

    private final ChineseTokenizer tokenizer;

    private final double k1;

    private final double b;

    public BM25Scorer(
            ChineseTokenizer tokenizer,
            @Value("${paiagent.rag.retrieval.bm25.k1:1.2}") double k1,
            @Value("${paiagent.rag.retrieval.bm25.b:0.75}") double b) {
        this.tokenizer = tokenizer;
        this.k1 = k1;
        this.b = b;
    }

    /**
     * Score a single document against query tokens using BM25.
     *
     * @param queryTokens tokenized query terms
     * @param document    the document content
     * @param avgDocLength average document length across the collection
     * @param totalDocs    total number of documents in the collection
     * @param docFreq      document frequency: term → number of documents containing it
     * @return BM25 score
     */
    public double score(List<String> queryTokens, String document,
                        double avgDocLength, int totalDocs,
                        Map<String, Integer> docFreq) {
        if (queryTokens.isEmpty() || document == null || document.isEmpty()) {
            return 0.0;
        }

        Map<String, Integer> tf = termFrequency(document);
        double docLength = tokenizeLength(document);
        double scoreSum = 0.0;

        for (String term : queryTokens) {
            int freq = tf.getOrDefault(term, 0);
            if (freq == 0) {
                continue;
            }
            int df = docFreq.getOrDefault(term, 0);
            double idf = idf(totalDocs, df);
            double numerator = freq * (k1 + 1.0);
            double denominator = freq + k1 * (1.0 - b + b * docLength / avgDocLength);
            scoreSum += idf * numerator / denominator;
        }

        return scoreSum;
    }

    /**
     * Build a term frequency map for a document.
     */
    public Map<String, Integer> termFrequency(String document) {
        if (document == null || document.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Integer> tf = new HashMap<>();
        for (String token : tokenizer.tokenize(document)) {
            tf.merge(token, 1, Integer::sum);
        }
        return tf;
    }

    /**
     * Score a batch of candidate chunks against a query using BM25.
     * Computes document frequencies and average length on-the-fly from the batch.
     *
     * @param query      the search query
     * @param candidates the candidate chunks to score
     * @return map of chunkId → BM25 score, ordered by score descending
     */
    public Map<Long, Double> scoreBatch(String query, List<KnowledgeChunk> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyMap();
        }

        List<String> queryTokens = tokenizer.searchTerms(query);
        if (queryTokens.isEmpty()) {
            return Collections.emptyMap();
        }

        // Build per-document term frequencies and compute document frequencies
        List<Map<String, Integer>> docTfs = new ArrayList<>();
        List<Integer> docLengths = new ArrayList<>();
        Map<String, Integer> docFreq = new HashMap<>();

        for (KnowledgeChunk chunk : candidates) {
            String content = buildScorableContent(chunk);
            Map<String, Integer> tf = termFrequency(content);
            docTfs.add(tf);
            docLengths.add(tokenizeLength(content));

            // Count each term once per document for DF
            for (String term : tf.keySet()) {
                docFreq.merge(term, 1, Integer::sum);
            }
        }

        int totalDocs = candidates.size();
        double avgDocLength = docLengths.stream()
                .mapToInt(Integer::intValue)
                .average()
                .orElse(1.0);
        if (avgDocLength < 1.0) {
            avgDocLength = 1.0;
        }

        // Score each candidate
        Map<Long, Double> scores = new LinkedHashMap<>();
        for (int i = 0; i < candidates.size(); i++) {
            KnowledgeChunk chunk = candidates.get(i);
            Map<String, Integer> tf = docTfs.get(i);
            double scoreSum = 0.0;

            for (String term : queryTokens) {
                int freq = tf.getOrDefault(term, 0);
                if (freq == 0) {
                    continue;
                }
                int df = docFreq.getOrDefault(term, 0);
                double idf = idf(totalDocs, df);
                double numerator = freq * (k1 + 1.0);
                double denominator = freq + k1 * (1.0 - b + b * docLengths.get(i) / avgDocLength);
                scoreSum += idf * numerator / denominator;
            }

            scores.put(chunk.getId(), scoreSum);
        }

        // Sort by score descending
        return scores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (a, b) -> a,
                        LinkedHashMap::new
                ));
    }

    /**
     * Normalize a BM25 score to [0, 1] range using a simple saturation function.
     * This makes BM25 scores comparable with dense vector scores.
     */
    public double normalizeScore(double bm25Score, double maxObservedScore) {
        if (maxObservedScore <= 0.0) {
            return 0.0;
        }
        return Math.min(1.0, bm25Score / maxObservedScore);
    }

    private double idf(int totalDocs, int docFreq) {
        if (docFreq <= 0 || totalDocs <= 0) {
            return 0.0;
        }
        // Standard IDF: log((N - df + 0.5) / (df + 0.5) + 1)
        return Math.log((totalDocs - docFreq + 0.5) / (docFreq + 0.5) + 1.0);
    }

    private String buildScorableContent(KnowledgeChunk chunk) {
        StringBuilder sb = new StringBuilder();
        if (chunk.getContent() != null) {
            sb.append(chunk.getContent());
        }
        if (chunk.getSourceName() != null) {
            sb.append(' ').append(chunk.getSourceName());
        }
        if (chunk.getSectionTitle() != null) {
            sb.append(' ').append(chunk.getSectionTitle());
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    private int tokenizeLength(String text) {
        return tokenizer.tokenize(text).size();
    }
}
