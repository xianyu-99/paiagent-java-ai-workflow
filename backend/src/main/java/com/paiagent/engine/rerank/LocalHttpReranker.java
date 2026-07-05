package com.paiagent.engine.rerank;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.paiagent.service.rag.RetrievalCandidate;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adapter for a local Cross-Encoder reranker service.
 * Expected request:
 * {"query":"...","documents":["..."],"top_n":12}
 * Supported responses:
 * {"results":[{"index":0,"relevance_score":0.92}]} or {"scores":[0.92,0.31]}.
 */
public class LocalHttpReranker implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(LocalHttpReranker.class);

    private final boolean enabled;
    private final String baseUrl;
    private final int timeoutMs;
    private final RestClient restClient;

    public LocalHttpReranker(RerankerProperties properties) {
        this.enabled = properties.isLocalEnabled();
        this.baseUrl = properties.getLocalBaseUrl();
        this.timeoutMs = properties.getTimeoutMs() > 0 ? properties.getTimeoutMs() : 2_000;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeoutMs);
        requestFactory.setReadTimeout(timeoutMs);
        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(this.baseUrl == null ? "" : this.baseUrl)
                .build();
    }

    @Override
    public boolean isAvailable() {
        return enabled && StringUtils.hasText(baseUrl);
    }

    @Override
    public List<RetrievalCandidate> rerank(String query, List<RetrievalCandidate> candidates) {
        if (!isAvailable() || candidates == null || candidates.isEmpty()) {
            return candidates == null ? List.of() : candidates;
        }

        List<String> documents = new ArrayList<>();
        Map<Integer, RetrievalCandidate> indexToCandidate = new LinkedHashMap<>();
        for (RetrievalCandidate candidate : candidates) {
            String content = candidate.chunk() != null && candidate.chunk().getContent() != null
                    ? candidate.chunk().getContent()
                    : candidate.contextContent();
            if (StringUtils.hasText(content)) {
                int documentIndex = documents.size();
                documents.add(content);
                indexToCandidate.put(documentIndex, candidate);
            }
        }
        if (documents.isEmpty()) {
            return candidates;
        }

        try {
            LocalRerankResponse response = restClient.post()
                    .uri("")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "query", query == null ? "" : query,
                            "documents", documents,
                            "top_n", documents.size()
                    ))
                    .retrieve()
                    .body(LocalRerankResponse.class);
            applyScores(response, indexToCandidate);
            candidates.sort(Comparator.comparing(
                    (RetrievalCandidate candidate) -> safe(candidate.rerankScore())).reversed());
            for (int i = 0; i < candidates.size(); i++) {
                candidates.get(i).rank(i + 1);
            }
            return candidates;
        } catch (Exception e) {
            log.warn("LocalHttpReranker failed, returning candidates as-is: {}", e.getMessage());
            return candidates;
        }
    }

    private void applyScores(LocalRerankResponse response, Map<Integer, RetrievalCandidate> indexToCandidate) {
        if (response == null) {
            return;
        }
        List<LocalRerankResult> results = response.results();
        if (!results.isEmpty()) {
            for (LocalRerankResult result : results) {
                RetrievalCandidate candidate = indexToCandidate.get(result.getIndex());
                Double score = result.score();
                if (candidate != null && score != null) {
                    candidate.rerankScore(score);
                }
            }
            return;
        }
        List<Double> scores = response.getScores();
        if (scores == null || scores.isEmpty()) {
            return;
        }
        for (int i = 0; i < scores.size(); i++) {
            RetrievalCandidate candidate = indexToCandidate.get(i);
            if (candidate != null && scores.get(i) != null) {
                candidate.rerankScore(scores.get(i));
            }
        }
    }

    private double safe(Double score) {
        return score == null ? 0.0 : score;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class LocalRerankResponse {
        private List<LocalRerankResult> results;
        private List<Double> scores;

        private List<LocalRerankResult> results() {
            return results == null ? List.of() : results;
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class LocalRerankResult {
        private Integer index;
        @JsonProperty("relevance_score")
        private Double relevanceScore;
        private Double score;

        private Double score() {
            return relevanceScore != null ? relevanceScore : score;
        }
    }
}
