package com.paiagent.engine.rerank;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiagent.service.embedding.EmbeddingApiKeyResolver;
import com.paiagent.service.rag.RetrievalCandidate;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DashScope (Alibaba Bailian) Reranker using the gte-rerank model.
 * <p>
 * Calls the DashScope Rerank API to re-score retrieval candidates as
 * query-document pairs via a Cross-Encoder, producing substantially
 * more accurate relevance scores than linear fusion.
 * <p>
 * Falls back gracefully: if the API is unreachable, returns candidates
 * unchanged (they will retain their pre-rerank scores).
 */
public class DashScopeReranker implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(DashScopeReranker.class);

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final RestClient restClient;

    private final String baseUrl;

    private final String model;

    private final boolean enabled;

    private final int timeoutMs;

    private final int maxRetries;

    private final EmbeddingApiKeyResolver apiKeyResolver;

    public DashScopeReranker(RerankerProperties properties) {
        this.enabled = properties.isEnabled();
        this.model = properties.getModel() != null ? properties.getModel() : "gte-rerank";
        this.baseUrl = properties.getBaseUrl() != null ? properties.getBaseUrl()
                : "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";
        this.timeoutMs = properties.getTimeoutMs() > 0 ? properties.getTimeoutMs() : 10_000;
        this.maxRetries = properties.getMaxRetries() >= 0 ? properties.getMaxRetries() : 2;
        this.apiKeyResolver = new EmbeddingApiKeyResolver();
        this.restClient = RestClient.builder()
                .baseUrl(this.baseUrl)
                .build();
    }

    @Override
    public boolean isAvailable() {
        if (!enabled) {
            log.debug("DashScopeReranker is disabled");
            return false;
        }
        String apiKey = apiKeyResolver.resolve();
        if (!StringUtils.hasText(apiKey)) {
            log.debug("DashScopeReranker has no API key");
            return false;
        }
        return true;
    }

    @Override
    public List<RetrievalCandidate> rerank(String query, List<RetrievalCandidate> candidates) {
        if (!isAvailable() || candidates.isEmpty()) {
            log.debug("DashScopeReranker not available or empty candidates, returning as-is");
            return candidates;
        }

        String apiKey = apiKeyResolver.resolve();
        if (!StringUtils.hasText(apiKey)) {
            log.warn("DashScopeReranker: no API key resolved, returning candidates as-is");
            return candidates;
        }

        // Build documents from candidates
        List<String> documents = new ArrayList<>();
        Map<Integer, RetrievalCandidate> indexToCandidate = new LinkedHashMap<>();
        for (int i = 0; i < candidates.size(); i++) {
            RetrievalCandidate c = candidates.get(i);
            String content = c.chunk() != null && c.chunk().getContent() != null
                    ? c.chunk().getContent() : c.contextContent();
            if (StringUtils.hasText(content)) {
                documents.add(content);
                indexToCandidate.put(i, c);
            }
        }

        if (documents.isEmpty()) {
            return candidates;
        }

        double[] scores = new double[candidates.size()];
        for (int retry = 0; retry <= maxRetries; retry++) {
            try {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("model", model);
                body.put("input", Map.of("query", query, "documents", documents));
                body.put("parameters", Map.of("top_n", Math.min(documents.size(), 50)));

                DashScopeRerankResponse response = restClient.post()
                        .uri("")
                        .header("Authorization", "Bearer " + apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(DashScopeRerankResponse.class);

                if (response != null && response.getOutput() != null
                        && response.getOutput().getResults() != null) {
                    for (DashScopeRerankResult result : response.getOutput().getResults()) {
                        int idx = result.getIndex();
                        if (idx >= 0 && idx < scores.length && result.getRelevanceScore() != null) {
                            scores[idx] = result.getRelevanceScore();
                        }
                    }
                }
                log.debug("DashScopeReranker: reranked {} documents in {}ms",
                        documents.size(), response != null ? response.getUsage().getTotalTokens() : 0);
                break; // success
            } catch (ResourceAccessException e) {
                log.warn("DashScopeReranker: connection failed (attempt {}/{}): {}",
                        retry + 1, maxRetries + 1, e.getMessage());
                if (retry >= maxRetries) {
                    log.warn("DashScopeReranker: all retries exhausted, returning candidates as-is");
                    return candidates;
                }
                sleepBackoff(retry);
            } catch (RestClientResponseException e) {
                log.warn("DashScopeReranker: HTTP {} (attempt {}/{}): {}",
                        e.getStatusCode().value(), retry + 1, maxRetries + 1, e.getMessage());
                if (isRetryable(e.getStatusCode().value())) {
                    if (retry >= maxRetries) {
                        log.warn("DashScopeReranker: all retries exhausted");
                        return candidates;
                    }
                    sleepBackoff(retry);
                } else {
                    log.warn("DashScopeReranker: non-retryable error, returning candidates as-is");
                    return candidates;
                }
            } catch (Exception e) {
                log.warn("DashScopeReranker: unexpected error: {}", e.getMessage());
                return candidates;
            }
        }

        // Update scores and re-sort
        for (int i = 0; i < scores.length; i++) {
            if (scores[i] > 0) {
                candidates.get(i).rerankScore(scores[i]);
            }
        }
        candidates.sort(Comparator.comparing(
                (RetrievalCandidate c) -> c.rerankScore() != null ? c.rerankScore() : 0.0).reversed());

        // Update rank
        for (int i = 0; i < candidates.size(); i++) {
            candidates.get(i).rank(i + 1);
        }

        return candidates;
    }

    private void sleepBackoff(int retry) {
        try {
            Thread.sleep(Duration.ofMillis((long) (200 * Math.pow(2, retry))));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean isRetryable(int httpStatus) {
        return httpStatus == 429 || httpStatus == 408 || httpStatus >= 500;
    }

    // ---- JSON response mapping ----

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class DashScopeRerankResponse {
        private DashScopeOutput output;
        private DashScopeUsage usage;
        @JsonProperty("request_id")
        private String requestId;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class DashScopeOutput {
        private List<DashScopeRerankResult> results;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class DashScopeRerankResult {
        private Integer index;
        @JsonProperty("relevance_score")
        private Double relevanceScore;
        private String document;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class DashScopeUsage {
        @JsonProperty("total_tokens")
        private Integer totalTokens;
    }
}
