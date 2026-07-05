package com.paiagent.engine.rerank;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.paiagent.service.embedding.EmbeddingApiKeyResolver;
import com.paiagent.service.rag.RetrievalCandidate;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
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
 * DashScope (Alibaba Bailian) Reranker using a dedicated text rerank model.
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

    private final RestClient restClient;

    private final String baseUrl;

    private final String model;

    private final boolean enabled;

    private final int timeoutMs;

    private final int maxRetries;

    private final EmbeddingApiKeyResolver apiKeyResolver;

    /** Set to true when the API returns AccessDenied (403), so future isAvailable() checks fail. */
    private volatile boolean accessDenied = false;

    public DashScopeReranker(RerankerProperties properties) {
        this.enabled = properties.isEnabled();
        this.model = properties.getModel() != null ? properties.getModel() : "gte-rerank-v2";
        this.baseUrl = properties.getBaseUrl() != null ? properties.getBaseUrl()
                : "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";
        this.timeoutMs = properties.getTimeoutMs() > 0 ? properties.getTimeoutMs() : 10_000;
        this.maxRetries = properties.getMaxRetries() >= 0 ? properties.getMaxRetries() : 2;
        this.apiKeyResolver = new EmbeddingApiKeyResolver();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(this.timeoutMs);
        requestFactory.setReadTimeout(this.timeoutMs);
        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(this.baseUrl)
                .build();
    }

    @Override
    public boolean isAvailable() {
        if (accessDenied) {
            return false;
        }
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
                int documentIndex = documents.size();
                documents.add(content);
                indexToCandidate.put(documentIndex, c);
            }
        }

        if (documents.isEmpty()) {
            return candidates;
        }

        for (int retry = 0; retry <= maxRetries; retry++) {
            try {
                Map<String, Object> body = buildRequestBody(query, documents);

                DashScopeRerankResponse response = restClient.post()
                        .uri("")
                        .header("Authorization", "Bearer " + apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(DashScopeRerankResponse.class);

                List<DashScopeRerankResult> results = response == null ? List.of() : response.results();
                if (!results.isEmpty()) {
                    for (DashScopeRerankResult result : results) {
                        int idx = result.getIndex();
                        RetrievalCandidate candidate = indexToCandidate.get(idx);
                        if (candidate != null && result.getRelevanceScore() != null) {
                            candidate.rerankScore(result.getRelevanceScore());
                        }
                    }
                }
                Integer totalTokens = response != null && response.getUsage() != null
                        ? response.getUsage().getTotalTokens()
                        : 0;
                log.debug("DashScopeReranker: reranked {} documents, totalTokens={}",
                        documents.size(), totalTokens);
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
                if (isNonRetryableConfigurationError(e.getStatusCode().value())) {
                    log.warn("DashScopeReranker: non-retryable configuration error HTTP {}, marking unavailable",
                            e.getStatusCode().value());
                    accessDenied = true;
                    return candidates;
                }
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

        candidates.sort(Comparator.comparing(
                (RetrievalCandidate c) -> c.rerankScore() != null ? c.rerankScore() : 0.0).reversed());

        // Update rank
        for (int i = 0; i < candidates.size(); i++) {
            candidates.get(i).rank(i + 1);
        }

        return candidates;
    }

    private Map<String, Object> buildRequestBody(String query, List<String> documents) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        if (isQwen3Rerank()) {
            body.put("query", query);
            body.put("documents", documents);
            body.put("top_n", Math.min(documents.size(), 50));
            body.put("instruct", "Given a user question, retrieve passages that answer the question.");
            return body;
        }
        body.put("input", Map.of("query", query, "documents", documents));
        body.put("parameters", Map.of("top_n", Math.min(documents.size(), 50)));
        return body;
    }

    private boolean isQwen3Rerank() {
        return "qwen3-rerank".equalsIgnoreCase(model);
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

    private boolean isNonRetryableConfigurationError(int httpStatus) {
        return httpStatus == 400 || httpStatus == 401 || httpStatus == 403 || httpStatus == 404;
    }

    // ---- JSON response mapping ----

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class DashScopeRerankResponse {
        private List<DashScopeRerankResult> results;
        private DashScopeOutput output;
        private DashScopeUsage usage;
        @JsonProperty("request_id")
        private String requestId;

        private List<DashScopeRerankResult> results() {
            if (results != null) {
                return results;
            }
            if (output != null && output.getResults() != null) {
                return output.getResults();
            }
            return List.of();
        }
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
        private Object document;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class DashScopeUsage {
        @JsonProperty("total_tokens")
        private Integer totalTokens;
    }
}
